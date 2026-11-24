package com.example.projecte_research;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ResearchNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(projecte_research.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ResearchNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, SyncProgressPacket.class,
                SyncProgressPacket::encode, SyncProgressPacket::decode, SyncProgressPacket::handle);
    }
}