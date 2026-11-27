package com.example.projecte_research.mixin;

import com.example.projecte_research.ContributionManager;
import com.example.projecte_research.DepositTracker;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.example.projecte_research.EmcSuppression;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import java.math.BigInteger;

@Mixin(targets = "moze_intel.projecte.impl.capability.KnowledgeImpl$DefaultImpl", remap = false)
public abstract class KnowledgeProviderMixin {

    @Shadow(remap = false)
    private Player player;

    /**
     * Переопределяет default-метод IKnowledgeProvider.addKnowledge(ItemStack):
     * его используют блоки и шины других модов, обходящие слот поглощения.
     *
     * Если мы внутри явного сеанса (стол, Expansion, AppliedE) - считаем по точному количеству.
     * Если источник неизвестный - передаём дальше как обычное изучение и запоминаем количество
     * из стака как подсказку; окончательно вклад рассчитает DepositTracker по приросту EMC.
     */
    public boolean addKnowledge(ItemStack stack) {
        IKnowledgeProvider self = (IKnowledgeProvider) (Object) this;
        ServerPlayer owner = player instanceof ServerPlayer sp ? sp : null;
        if (owner == null || stack.isEmpty() || DepositTracker.isBypassed()
                || ContributionManager.EXTERNAL_AMOUNT.get() != null
                || com.example.projecte_research.EmcSuppression.explicitActive()) {
            return ContributionManager.tryLearn(self, owner, stack);
        }
        DepositTracker.setHint(stack.getCount());
        try {
            return self.addKnowledge(ItemInfo.fromStack(stack));
        } finally {
            DepositTracker.clearHint();
        }
    }

    /**
     * Единственная точка, через которую проходит ЛЮБОЕ изучение предмета любым модом.
     * Здесь мы не знаем количество, поэтому только сообщаем DepositTracker, что предмет выучен.
     * require = 0: если в другой версии ProjectE метода нет, mixin просто не сработает, а не уронит игру.
     */
    @Inject(method = "addKnowledge(Lmoze_intel/projecte/api/ItemInfo;)Z",
            at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void research$trackLearn(ItemInfo info, CallbackInfoReturnable<Boolean> cir) {
        IKnowledgeProvider self = (IKnowledgeProvider) (Object) this;
        ServerPlayer owner = player instanceof ServerPlayer sp ? sp : null;

        if (DepositTracker.isBypassed() || ContributionManager.finishOffer(owner, info, cir.getReturnValueZ())) {
            return;
        }
        if (cir.getReturnValueZ() && !DepositTracker.onLearned(self, owner, info)) {
            cir.setReturnValue(false);
        }
    }

    @ModifyVariable(method = "setEmc(Ljava/math/BigInteger;)V",
            at = @At("HEAD"), argsOnly = true, remap = false)
    private BigInteger research$guardEmc(BigInteger emc) {
        IKnowledgeProvider self = (IKnowledgeProvider) (Object) this;
        ServerPlayer owner = player instanceof ServerPlayer sp ? sp : null;
        return EmcSuppression.onSetEmc(self, owner, emc);
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
