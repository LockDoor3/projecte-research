package com.example.projecte_research;

import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.utils.EMCHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = projecte_research.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientTooltipEvents {

    // LOW - чтобы наша строка добавилась ПОСЛЕ строки "Unlearned" от ProjectE
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !Screen.hasShiftDown()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null || !EMCHelper.doesItemHaveEmc(stack)) {
            return;
        }
        boolean learned = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)
                .map(provider -> provider.hasKnowledge(stack))
                .orElse(true);
        if (learned) {
            return; // изучен - счётчика нет
        }
        int have = ClientProgress.get(stack.getItem());
        event.getToolTip().add(
                Component.literal("(" + have + "/" + ClientProgress.required(stack.getItem()) + ")")
                        .withStyle(ChatFormatting.GRAY));
    }
}