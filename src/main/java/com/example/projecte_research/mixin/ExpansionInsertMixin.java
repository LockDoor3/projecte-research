package com.example.projecte_research.mixin;

import com.example.projecte_research.ContributionManager;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.example.projecte_research.EmcSuppression;

@Pseudo
@Mixin(targets = {
        "cool.furry.mc.forge.projectexpansion.block.entity.BlockEntityTransmutationInterface$ItemHandler",
        "cool.furry.mc.forge.projectexpansion.block.entity.BlockEntityEMCLink$ItemHandler"
}, remap = false)
public abstract class ExpansionInsertMixin {

    private static final String INSERT =
            "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;";

    @Inject(method = INSERT, at = @At("HEAD"), remap = false)
    private void research$captureAmount(int slot, ItemStack stack, boolean simulate,
                                        CallbackInfoReturnable<ItemStack> cir) {
        ContributionManager.EXTERNAL_AMOUNT.set((long) stack.getCount());
        EmcSuppression.begin();
    }

    @Redirect(method = INSERT,
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I", remap = false),
            remap = false, require = 0)
    private int research$captureLimited(int count, int limit) {
        int inserted = Math.min(count, limit);
        ContributionManager.EXTERNAL_AMOUNT.set((long) inserted);
        return inserted;
    }

    @Inject(method = INSERT, at = @At("RETURN"), remap = false)
    private void research$clearAmount(int slot, ItemStack stack, boolean simulate,
                                      CallbackInfoReturnable<ItemStack> cir) {
        ContributionManager.EXTERNAL_AMOUNT.remove();
        EmcSuppression.end();
    }
}