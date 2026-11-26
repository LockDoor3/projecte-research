package com.example.projecte_research.mixin;

import com.example.projecte_research.EmcSuppression;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Оборачивает любой клик в меню стола трансмутации (слот поглощения, шифт-клик и т.д.)
 * в "сеанс" для опции noEmcWhileLearning, см. EmcSuppression.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class TransmutationEmcScopeMixin {

    private static final String CLICKED =
            "clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V";

    @Unique
    private boolean research$appliesTo(Player player) {
        if (!(player instanceof ServerPlayer)) return false;
        String name = this.getClass().getName();
        return name.startsWith("moze_intel.projecte.") && name.contains("Transmutation");
    }

    @Inject(method = CLICKED, at = @At("HEAD"))
    private void research$beginEmcScope(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (research$appliesTo(player)) EmcSuppression.begin();
    }

    @Inject(method = CLICKED, at = @At("RETURN"))
    private void research$endEmcScope(int slotId, int button, ClickType type, Player player, CallbackInfo ci) {
        if (research$appliesTo(player)) EmcSuppression.end();
    }
}