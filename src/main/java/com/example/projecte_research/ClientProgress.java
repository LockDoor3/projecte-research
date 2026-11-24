package com.example.projecte_research;

import net.minecraft.world.item.Item;

import java.util.Map;

public final class ClientProgress {
    private static volatile Map<String, Integer> progress = Map.of();
    private static volatile ResearchConfig.Data config = null;
    private static volatile int version = 0;

    private ClientProgress() {}

    public static void set(ResearchConfig.Data cfg, Map<String, Integer> map) {
        config = cfg;
        progress = Map.copyOf(map);
        version++;
    }

    public static int get(Item item) {
        return progress.getOrDefault(ContributionManager.keyOf(item), 0);
    }

    public static Map<String, Integer> all() {
        return progress;
    }

    public static int version() {
        return version;
    }

    public static int required(Item item) {
        ResearchConfig.Data cfg = config != null ? config : ResearchConfig.current();
        return cfg.requiredFor(item);
    }
}