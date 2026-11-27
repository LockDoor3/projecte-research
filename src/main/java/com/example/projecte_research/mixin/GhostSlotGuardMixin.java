package com.example.projecte_research.mixin;

import com.example.projecte_research.LearnGuard;
import com.example.projecte_research.ResearchConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class GhostSlotGuardMixin {

    private static final String CLICKED =
            "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V";
    private static final String CLICKED_SRG = "m_150399_(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V";


    @Unique
    private ItemStack[] research$ghostBefore;


    @Inject(method = {CLICKED, CLICKED_SRG}, at = @At("HEAD"), remap = false)
    private void research$snapshot(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        research$ghostBefore = null;
        if (!(player instanceof ServerPlayer) || !ResearchConfig.current().condenserRequiresLearned()) return;
        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        if (!LearnGuard.isGuardedMenu(menu)) return;

        ItemStack[] copy = new ItemStack[menu.slots.size()];
        for (int i = 0; i < copy.length; i++) {
            Slot slot = menu.slots.get(i);
            if (LearnGuard.isGhostSlot(slot)) copy[i] = slot.getItem().copy();
        }
        research$ghostBefore = copy;
    }

    @Inject(method = {CLICKED, CLICKED_SRG}, at = @At("RETURN"))
    private void research$verify(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        ItemStack[] before = research$ghostBefore;
        research$ghostBefore = null;
        if (before == null) return;

        AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
        for (int i = 0; i < before.length && i < menu.slots.size(); i++) {
            if (before[i] == null) continue;
            Slot slot = menu.slots.get(i);
            ItemStack now = slot.getItem();
            if (now.isEmpty() || ItemStack.isSameItemSameTags(before[i], now)) continue;
            if (!LearnGuard.isAllowed(player, now)) {
                slot.set(before[i]); // вернуть прежнее значение слота
                LearnGuard.deny(player);
            }
        }
    }
}