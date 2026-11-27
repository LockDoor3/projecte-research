package com.example.projecte_research;

import moze_intel.projecte.api.ItemInfo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.IdentityHashMap;

/** Track live inputs only on the authoritative server thread, until the end of its tick. */
public final class LearningSource {
    private static final ThreadLocal<IdentityHashMap<ItemInfo, ItemStack>> SOURCES =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private LearningSource() {}

    public static void capture(ItemInfo key, ItemStack source) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread() && !source.isEmpty()) {
            SOURCES.get().put(key, source);
        }
    }

    public static void normalized(ItemInfo original, ItemInfo normalized) {
        if (original == normalized) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isSameThread()) {
            ItemStack stack = find(original);
            if (!stack.isEmpty()) SOURCES.get().put(normalized, stack);
        }
    }

    public static ItemStack find(ItemInfo key) {
        return SOURCES.get().getOrDefault(key, ItemStack.EMPTY);
    }

    public static void clear() {
        SOURCES.remove();
    }
}
