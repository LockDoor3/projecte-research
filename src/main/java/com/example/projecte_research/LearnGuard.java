package com.example.projecte_research;

import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.utils.EMCHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class LearnGuard {
    private LearnGuard() {}

    /** true, если предмет можно использовать: функция выключена, у предмета нет EMC или он изучен. */
    public static boolean isAllowed(Player player, ItemStack stack) {
        if (!ResearchConfig.current().condenserRequiresLearned()) return true;
        if (stack.isEmpty() || !EMCHelper.doesItemHaveEmc(stack)) return true;
        return player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)
                .map(provider -> provider.hasKnowledge(stack))
                .orElse(true);
    }

    public static void deny(Player player) {
        if (player instanceof ServerPlayer sp) {
            sp.displayClientMessage(
                    Component.translatable("message.projecte_research.condenser_unlearned"), true);
        }
    }

    /** Меню сборщиков (ProjectE и Expansion) и Ртутного ока. Проверка по имени класса,
     *  чтобы не подключать jar Expansion и не зависеть от точных названий. */
    public static boolean isGuardedMenu(AbstractContainerMenu menu) {
        String name = menu.getClass().getName();
        boolean projecte = name.startsWith("moze_intel.projecte.")
                && (name.contains("Collector") || name.contains("MercurialEye"));
        boolean expansion = name.startsWith("cool.furry.mc.") && name.contains("Collector");
        return projecte || expansion;
    }

    public static boolean isGhostSlot(Slot slot) {
        return slot.getClass().getSimpleName().contains("Ghost");
    }
}