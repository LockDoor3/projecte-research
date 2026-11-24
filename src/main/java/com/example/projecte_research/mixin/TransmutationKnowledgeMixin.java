package com.example.projecte_research.mixin;

import com.example.projecte_research.ContributionManager;
import moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TransmutationInventory.class, remap = false)
public abstract class TransmutationKnowledgeMixin {

    @Inject(method = "handleKnowledge(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), remap = false)
    private void research$begin(ItemStack stack, CallbackInfo ci) {
        ContributionManager.CURRENT_COUNT.set(stack.getCount());
    }

    @Inject(method = "handleKnowledge(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"), remap = false)
    private void research$end(ItemStack stack, CallbackInfo ci) {
        ContributionManager.CURRENT_COUNT.remove();
    }
}