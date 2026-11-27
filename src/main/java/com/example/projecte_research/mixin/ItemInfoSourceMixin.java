package com.example.projecte_research.mixin;

import com.example.projecte_research.LearningSource;
import moze_intel.projecte.api.ItemInfo;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ItemInfo.class, remap = false)
public abstract class ItemInfoSourceMixin {
    @Inject(method = "fromStack", at = @At("RETURN"))
    private static void research$source(ItemStack stack, CallbackInfoReturnable<ItemInfo> cir) {
        LearningSource.capture(cir.getReturnValue(), stack);
    }
}
