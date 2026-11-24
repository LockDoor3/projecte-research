package com.example.projecte_research.mixin;

import com.example.projecte_research.ContributionManager;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "moze_intel.projecte.impl.capability.KnowledgeImpl$DefaultImpl", remap = false)
public abstract class KnowledgeProviderMixin {

    @Shadow(remap = false)
    private Player player;

    /**
     * Переопределяет default-метод IKnowledgeProvider.addKnowledge(ItemStack):
     * его используют блоки и шины других модов, обходящие слот поглощения.
     */
    public boolean addKnowledge(ItemStack stack) {
        IKnowledgeProvider self = (IKnowledgeProvider) (Object) this;
        ServerPlayer owner = player instanceof ServerPlayer sp ? sp : null;
        return ContributionManager.tryLearn(self, owner, stack);
    }

    /**
     * Некоторые моды (AppliedE) шлют клиенту "предмет изучен" безусловно, даже если
     * addKnowledge вернул false. Не даём клиенту узнать то, чего нет на сервере.
     */
    @Inject(method = "syncKnowledgeChange(Lnet/minecraft/server/level/ServerPlayer;Lmoze_intel/projecte/api/ItemInfo;Z)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void research$noFalseLearned(ServerPlayer target, ItemInfo change, boolean learned, CallbackInfo ci) {
        if (learned && !((IKnowledgeProvider) (Object) this).hasKnowledge(change)) {
            ci.cancel();
        }
    }
}