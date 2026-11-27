package com.example.projecte_research.mixin;

import com.example.projecte_research.LearningSource;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.emc.nbt.NBTManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NBTManager.class, remap = false)
public abstract class PersistentInfoSourceMixin {
    @Inject(method = "getPersistentInfo", at = @At("RETURN"))
    private static void research$source(ItemInfo original, CallbackInfoReturnable<ItemInfo> cir) {
        LearningSource.normalized(original, cir.getReturnValue());
    }
}
