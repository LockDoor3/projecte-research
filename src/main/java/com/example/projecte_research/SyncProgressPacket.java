package com.example.projecte_research;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Map;
import java.util.function.Supplier;

public record SyncProgressPacket(ResearchConfig.Data config, Map<String, Integer> progress) {

    public static void encode(SyncProgressPacket p, FriendlyByteBuf buf) {
        p.config.write(buf);
        buf.writeMap(p.progress, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
    }

    public static SyncProgressPacket decode(FriendlyByteBuf buf) {
        ResearchConfig.Data config = ResearchConfig.Data.read(buf);
        Map<String, Integer> map = buf.readMap(FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt);
        return new SyncProgressPacket(config, map);
    }

    public static void handle(SyncProgressPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientProgress.set(p.config, p.progress));
        ctx.get().setPacketHandled(true);
    }
}