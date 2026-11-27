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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class CommonEvents {
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void onAttemptLearn(PlayerAttemptLearnEvent event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide) {
            event.setCanceled(true);
            return;
        }
        if (DepositTracker.isBypassed()) {
            return; // наш собственный вызов addKnowledge: прогресс уже учтён
        }

        Long external = ContributionManager.EXTERNAL_AMOUNT.get();
        Integer table = ContributionManager.CURRENT_COUNT.get();
        if (external == null && table == null && !EmcSuppression.explicitActive()) {
            ItemStack source = LearningSource.find(event.getSourceInfo());
            if (!source.isEmpty()) {
                if (!ContributionManager.offer(player, event.getReducedInfo(), source)) {
                    event.setCanceled(true);
                }
                return;
            }
        }
        int amount = external != null ? (int) Math.min(Integer.MAX_VALUE, external)
                : table != null ? table : 1;
        if (amount <= 0 || !ContributionManager.addProgress(player, event.getReducedInfo(), amount)) {
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

    /** Конец тика: добираем вклады, по которым так и не пришёл EMC (см. DepositTracker.flush). */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DepositTracker.flush();
            ContributionManager.clearOffers();
        }
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
