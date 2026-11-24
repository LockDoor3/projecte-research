package com.example.projecte_research;

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