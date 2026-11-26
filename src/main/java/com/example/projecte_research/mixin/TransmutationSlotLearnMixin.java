package com.example.projecte_research.mixin;

import moze_intel.projecte.gameObjs.container.inventory.TransmutationInventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = {
        "moze_intel.projecte.gameObjs.container.slots.transmutation.SlotInput",
        "moze_intel.projecte.gameObjs.container.slots.transmutation.SlotLock"
}, remap = false)
public abstract class TransmutationSlotLearnMixin {

    /**
     * Положили предмет в слот ввода или блокировки: он не съеден, поэтому изучение
     * не засчитываем. Изучение остаётся только у слота поглощения и у шифт-клика.
     *
     * Два имени метода: "set" в dev-среде, "m_5852_" (SRG) в обычном клиенте и на сервере.
     */
    @Redirect(method = {
            "set(Lnet/minecraft/world/item/ItemStack;)V",
            "m_5852_(Lnet/minecraft/world/item/ItemStack;)V"
    },
            at = @At(value = "INVOKE",
                    target = "Lmoze_intel/projecte/gameObjs/container/inventory/TransmutationInventory;handleKnowledge(Lnet/minecraft/world/item/ItemStack;)V",
                    remap = false),
            remap = false)
    private void research$noLearnOnPlace(TransmutationInventory inv, ItemStack stack) {
        // намеренно пусто
    }
}