package com.example.projecte_research;

import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.emc.nbt.NBTManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

public final class ContributionManager {
    private static final String ROOT = "projecte_research_progress";

    /** Сколько предметов в стаке, который сейчас обрабатывается (ставит mixin). */
    public static final ThreadLocal<Integer> CURRENT_COUNT = ThreadLocal.withInitial(() -> 1);

    /** Реальное количество предметов от внешнего мода. null - не задано. */
    public static final ThreadLocal<Long> EXTERNAL_AMOUNT = new ThreadLocal<>();

    private ContributionManager() {}

    private static CompoundTag persisted(Player player) {
        CompoundTag forgeData = player.getPersistentData();
        if (!forgeData.contains(Player.PERSISTED_NBT_TAG, Tag.TAG_COMPOUND)) {
            forgeData.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return forgeData.getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static CompoundTag data(Player player) {
        CompoundTag persisted = persisted(player);
        if (!persisted.contains(ROOT, Tag.TAG_COMPOUND)) {
            persisted.put(ROOT, new CompoundTag());
        }
        return persisted.getCompound(ROOT);
    }

    private static IKnowledgeProvider provider(Player player) {
        return player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null);
    }

    public static String keyOf(Item item) {
        return String.valueOf(ForgeRegistries.ITEMS.getKey(item));
    }

    public static int getProgress(Player player, Item item) {
        return data(player).getInt(keyOf(item));
    }

    // ---------- обычное накопление (поглощение в столе) ----------

    /** @return true, если набрано достаточно и предмет можно изучить */
    public static boolean addProgress(Player player, ItemInfo info, int amount) {
        Item item = info.getItem();
        int required = ResearchConfig.current().requiredFor(item);
        CompoundTag d = data(player);
        String key = keyOf(item);
        int total = d.getInt(key) + amount;

        boolean learned = total >= required;
        if (learned) {
            d.remove(key);
        } else {
            d.putInt(key, total);
            Component name = new ItemStack(item).getHoverName();
            player.displayClientMessage(
                    Component.literal("").append(name).append(Component.literal(": " + total + "/" + required)),
                    true);
        }

        if (player instanceof ServerPlayer sp) {
            sync(sp);
        }
        return learned;
    }

    // ---------- обучение «по стаку» через другие моды (Expansion, AppliedE и т.д.) ----------

    /** Заменяет default-метод IKnowledgeProvider.addKnowledge(ItemStack). */
    public static boolean tryLearn(IKnowledgeProvider provider, ServerPlayer owner, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ItemInfo info = ItemInfo.fromStack(stack);
        if (owner == null) {
            return provider.addKnowledge(info); // клиентская сторона: поведение как в оригинале
        }
        ItemInfo reduced = NBTManager.getPersistentInfo(info);
        if (provider.hasKnowledge(reduced)) {
            return false;
        }

        int count = stack.getCount();
        Long external = EXTERNAL_AMOUNT.get();
        if (external != null && external > 0) {
            count = (int) Math.min(external, Integer.MAX_VALUE);
        }

        if (addProgress(owner, reduced, count)) {
            return provider.addKnowledge(info);
        }
        return false;
    }

    // ---------- для команд ----------

    /** Задать точный прогресс. Если он >= нужного, предмет изучается, иначе изучение снимается. */
    public static boolean setProgress(ServerPlayer player, Item item, int amount) {
        int required = ResearchConfig.current().requiredFor(item);
        CompoundTag d = data(player);
        String key = keyOf(item);
        IKnowledgeProvider provider = provider(player);
        ItemInfo info = NBTManager.getPersistentInfo(ItemInfo.fromItem(item));

        boolean learned = amount >= required;
        if (learned) {
            d.remove(key);
            if (provider != null && provider.addKnowledge(info)) {
                provider.syncKnowledgeChange(player, info, true);
            }
        } else {
            if (amount > 0) {
                d.putInt(key, amount);
            } else {
                d.remove(key);
            }
            if (provider != null && provider.removeKnowledge(info)) {
                provider.syncKnowledgeChange(player, info, false);
            }
        }
        sync(player);
        return learned;
    }

    public static void clearItem(ServerPlayer player, Item item) {
        data(player).remove(keyOf(item));
        IKnowledgeProvider provider = provider(player);
        ItemInfo info = NBTManager.getPersistentInfo(ItemInfo.fromItem(item));
        if (provider != null && provider.removeKnowledge(info)) {
            provider.syncKnowledgeChange(player, info, false);
        }
        sync(player);
    }

    public static void clearAll(ServerPlayer player) {
        persisted(player).remove(ROOT);
        IKnowledgeProvider provider = provider(player);
        if (provider != null) {
            provider.clearKnowledge();
            provider.sync(player);
        }
        sync(player);
    }

    // ---------- синхронизация с клиентом ----------

    public static void sync(ServerPlayer player) {
        CompoundTag d = data(player);
        Map<String, Integer> map = new HashMap<>();
        for (String key : d.getAllKeys()) {
            map.put(key, d.getInt(key));
        }
        ResearchNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncProgressPacket(ResearchConfig.current(), map));
    }
}