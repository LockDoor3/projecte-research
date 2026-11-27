package com.example.projecte_research;

import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.emc.nbt.NBTManager;
import moze_intel.projecte.utils.EMCHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ContributionManager {
    private static final String ROOT = "projecte_research_progress";

    /** Сколько предметов в стаке, который сейчас обрабатывается (ставит mixin). */
    public static final ThreadLocal<Integer> CURRENT_COUNT = new ThreadLocal<>();

    private record PendingOffer(Player player, ItemInfo key, ItemStack stack, int count) {}
    private static final ThreadLocal<Deque<PendingOffer>> PENDING =
            ThreadLocal.withInitial(ArrayDeque::new);

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

    /** learned - порог набран; excess - сколько предметов сверх нужного (за них EMC идёт как обычно). */
    public record Result(boolean learned, long excess) {}

    /** Общий расчёт прогресса. Побочных эффектов, кроме сохранения прогресса и подсказки игроку, нет. */
    private static Result step(Player player, ItemInfo info, long amount) {
        Item item = info.getItem();
        int required = ResearchConfig.current().requiredFor(item);
        CompoundTag d = data(player);
        String key = keyOf(item);
        long previous = d.getInt(key);
        long total = previous + Math.max(0, amount);

        boolean learned = total >= required;
        if (learned) {
            d.remove(key);
        } else {
            d.putInt(key, (int) total);
            Component name = new ItemStack(item).getHoverName();
            player.displayClientMessage(
                    Component.literal("").append(name).append(Component.literal(": " + total + "/" + required)),
                    true);
        }

        if (player instanceof ServerPlayer sp) {
            sync(sp);
        }
        return new Result(learned, learned ? total - required : 0);
    }

    /** Для явных сеансов (стол, Expansion, AppliedE): заодно сообщает сеансу, сколько предметов учтено.
     *  @return true, если набрано достаточно и предмет можно изучить */
    public static boolean addProgress(Player player, ItemInfo info, int amount) {
        Result r = step(player, info, amount);
        // сколько предметов сверх нужного: за них EMC начисляется как обычно
        EmcSuppression.markLearning(amount, (int) Math.min(r.excess(), Integer.MAX_VALUE));
        return r.learned();
    }

    /** Для DepositTracker: то же самое, но без привязки к явному сеансу. */
    public static Result addProgressResult(Player player, ItemInfo info, long amount) {
        return step(player, info, amount);
    }

    public static boolean offer(Player player, ItemInfo key, ItemStack stack) {
        int needed = Math.max(1, ResearchConfig.current().requiredFor(key.getItem())
                - getProgress(player, key.getItem()));
        PendingOffer offer = new PendingOffer(player, key, stack, Math.min(needed, stack.getCount()));
        if (stack.getCount() >= needed) {
            PENDING.get().addLast(offer);
            return true;
        }
        commit(offer);
        return false;
    }

    public static boolean finishOffer(Player player, ItemInfo info, boolean accepted) {
        Iterator<PendingOffer> it = PENDING.get().descendingIterator();
        while (it.hasNext()) {
            PendingOffer offer = it.next();
            if (offer.player() == player && offer.key() == info) {
                it.remove();
                if (accepted) commit(offer);
                return true;
            }
        }
        return false;
    }

    private static void commit(PendingOffer offer) {
        Player player = offer.player();
        ItemStack stack = offer.stack();
        step(player, offer.key(), offer.count());
        if (ResearchConfig.current().noEmcWhileLearning()) {
            // Buffered sellers calculate the payout from the remaining input count.
            stack.shrink(offer.count());
        } else if (player instanceof ServerPlayer serverPlayer && isPlayerInput(player, stack)) {
            // A partial contribution cancels vanilla learning, so the caller can skip its sale.
            // Settle the offered stack here. A caller that continues sees zero items to sell.
            IKnowledgeProvider knowledge = provider(player);
            if (knowledge != null) {
                long unit = EMCHelper.getEmcSellValue(offer.key());
                BigInteger proceeds = BigInteger.valueOf(unit).multiply(BigInteger.valueOf(stack.getCount()));
                stack.shrink(stack.getCount());
                if (proceeds.signum() > 0) {
                    DepositTracker.bypass(() -> knowledge.setEmc(knowledge.getEmc().add(proceeds)));
                    knowledge.syncEmc(serverPlayer);
                }
            }
        }
        player.containerMenu.broadcastChanges();
    }

    private static boolean isPlayerInput(Player player, ItemStack stack) {
        if (player.containerMenu.getCarried() == stack) return true;
        for (var slot : player.containerMenu.slots) {
            if (slot.container == player.getInventory() && slot.getItem() == stack) return true;
        }
        return false;
    }

    public static void clearOffers() {
        PENDING.remove();
        LearningSource.clear();
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
            boolean[] result = new boolean[1];
            DepositTracker.bypass(() -> result[0] = provider.addKnowledge(info));
            return result[0];
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
            if (provider != null) {
                boolean[] added = new boolean[1];
                DepositTracker.bypass(() -> added[0] = provider.addKnowledge(info));
                if (added[0]) {
                    provider.syncKnowledgeChange(player, info, true);
                }
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
