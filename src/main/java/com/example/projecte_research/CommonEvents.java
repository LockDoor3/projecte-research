package com.example.projecte_research;

import moze_intel.projecte.api.event.PlayerAttemptCondenserSetEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.registries.ForgeRegistries;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import net.minecraft.network.chat.Component;
import moze_intel.projecte.api.event.PlayerAttemptLearnEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CommonEvents {
    @SubscribeEvent
    public void onAttemptLearn(PlayerAttemptLearnEvent event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide) {
            event.setCanceled(true);
            return;
        }
        int count = ContributionManager.CURRENT_COUNT.get();
        if (!ContributionManager.addProgress(player, event.getReducedInfo(), count)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onAttemptCondenserSet(PlayerAttemptCondenserSetEvent event) {
        if (!ResearchConfig.current().condenserRequiresLearned()) {
            return;
        }
        Player player = event.getPlayer();
        IKnowledgeProvider provider = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null);
        if (provider == null || !provider.hasKnowledge(event.getReducedInfo())) {
            event.setCanceled(true);
            if (player instanceof ServerPlayer sp) {
                sp.displayClientMessage(
                        Component.translatable("message.projecte_research.condenser_unlearned"), true);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        Player player = event.getEntity();
        if (player.isSecondaryUseActive()) return; // sneak: можно ставить блоки у ссылки
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) return;

        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(
                event.getLevel().getBlockState(event.getPos()).getBlock());
        if (id == null || !id.getNamespace().equals("projectexpansion")
                || !id.getPath().endsWith("_emc_link")) return;

        if (LearnGuard.isAllowed(player, held)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
        LearnGuard.deny(player);
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            ContributionManager.sync(sp);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        AddonCommands.register(event.getDispatcher(), event.getBuildContext());
    }
}