package com.example.projecte_research.mixin;

import com.example.projecte_research.ContributionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.example.projecte_research.EmcSuppression;

/**
 * Точечный hook под AppliedE: передаёт реальное количество вставляемых предметов
 * в счётчик (AppliedE вызывает addKnowledge со стаком из одного предмета).
 * @Pseudo: если AppliedE не установлен, mixin молча пропускается.
 */
@Pseudo
@Mixin(targets = "gripe._90.appliede.me.service.EMCStorage", remap = false)
public abstract class AppliedEInsertMixin {

    /** Запрошенное количество. */
    @ModifyVariable(
            method = "insertItem(Lappeng/api/stacks/AEItemKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;ZZLjava/lang/Runnable;)J",
            at = @At("HEAD"), argsOnly = true, remap = false)
    private long research$captureAmount(long amount) {
        ContributionManager.EXTERNAL_AMOUNT.set(amount);
        return amount;
    }

    @Inject(
            method = "insertItem(Lappeng/api/stacks/AEItemKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;ZZLjava/lang/Runnable;)J",
            at = @At("HEAD"), remap = false)
    private void research$beginEmcScope(CallbackInfoReturnable<Long> cir) {
        EmcSuppression.begin();
    }

    /** Количество после ограничения по энергии сети (если оно сработало). */
    @ModifyVariable(
            method = "insertItem(Lappeng/api/stacks/AEItemKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;ZZLjava/lang/Runnable;)J",
            at = @At("STORE"), argsOnly = true, remap = false, require = 0)
    private long research$captureReducedAmount(long amount) {
        ContributionManager.EXTERNAL_AMOUNT.set(amount);
        return amount;
    }

    @Inject(
            method = "insertItem(Lappeng/api/stacks/AEItemKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;ZZLjava/lang/Runnable;)J",
            at = @At("RETURN"), remap = false)
    private void research$clearAmount(CallbackInfoReturnable<Long> cir) {
        ContributionManager.EXTERNAL_AMOUNT.remove();
        EmcSuppression.end();
    }
}