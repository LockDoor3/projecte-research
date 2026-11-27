package com.example.projecte_research;

import com.google.gson.JsonElement;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResearchConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "projecte_research.json";

    private static final String DEFAULT_FILE = """
            {
                            "_help": "Rule priority: items > tags > nonStackableRequired > defaultRequired. nonStackableRequired applies to items that do not stack (max stack size 1); set it to -1 to disable. After editing this file run /projecte_research reload or restart the game.",
              
              "defaultRequired": 64,
              "nonStackableRequired": 1,
              
                             "_help": "condenserRequiresLearned: if true, the Energy Condenser (and MK2) lock slot only accepts items the player has learned; set to false to disable.",
              "condenserRequiresLearned": true,
            
                             "_help": "noEmcWhileLearning: if true, items that count toward learning (not yet learned) give NO EMC when inserted into the Transmutation Table, EMC Link, AE2 terminal with AppliedE, etc. Already learned items give EMC as usual. If false (default), EMC is given as usual.",
                "noEmcWhileLearning": true,

                             "_help": "debugFlow: if true, writes lines starting with [flow] to latest.log showing how other mods learn items and give EMC. Turn on only to diagnose compatibility problems.",
                "debugFlow": false,
                
                
              "tags": [
                { "tag": "forge:tools", "required": 1 },
                { "tag": "forge:armors", "required": 1 },
                { "tag": "minecraft:swords", "required": 1 },
                { "tag": "minecraft:pickaxes", "required": 1 },
                { "tag": "minecraft:axes", "required": 1 },
                { "tag": "minecraft:shovels", "required": 1 },
                { "tag": "minecraft:hoes", "required": 1 },
                { "tag": "minecraft:trimmable_armor", "required": 1 }
              ],
              "items": [
                { "id": "minecraft:stone_sword", "required": 1 }
              ]
            }
            """;

    public record TagRule(String tag, int required) {}

    public record Data(int defaultRequired, int nonStackableRequired,
                       Map<String, Integer> items, List<TagRule> tags,
                       boolean condenserRequiresLearned, boolean noEmcWhileLearning) {

        public int requiredFor(Item item) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) {
                Integer byItem = items.get(id.toString());
                if (byItem != null) return byItem;
            }
            ItemStack stack = new ItemStack(item);
            for (TagRule rule : tags) {
                ResourceLocation tagId = ResourceLocation.tryParse(rule.tag());
                if (tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId))) {
                    return rule.required();
                }
            }
            if (nonStackableRequired >= 1 && stack.getMaxStackSize() == 1) {
                return nonStackableRequired;
            }
            return defaultRequired;
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(defaultRequired);
            buf.writeInt(nonStackableRequired);
            buf.writeMap(items, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
            buf.writeCollection(tags, (b, t) -> {
                b.writeUtf(t.tag());
                b.writeVarInt(t.required());
            });
            buf.writeBoolean(condenserRequiresLearned);
        }

        public static Data read(FriendlyByteBuf buf) {
            int def = buf.readVarInt();
            int ns = buf.readInt();
            Map<String, Integer> items = buf.readMap(FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt);
            List<TagRule> tags = buf.readCollection(ArrayList::new, b -> new TagRule(b.readUtf(), b.readVarInt()));
            boolean condenser = buf.readBoolean();
            return new Data(def, ns, items, tags, condenser, false); // на клиенте опция не используется
        }
    }

    private static volatile boolean debugFlow;

    /** Диагностика совместимости: пишет в лог цепочки "изучение + EMC" от других модов. */
    public static boolean debugFlow() {
        return debugFlow;
    }

    private static volatile Data current = parse(JsonParser.parseString(DEFAULT_FILE).getAsJsonObject());

    private ResearchConfig() {}

    public static Data current() {
        return current;
    }

    public static void load() {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        try {
            if (!Files.exists(path)) {
                Files.writeString(path, DEFAULT_FILE);
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                apply(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read {}, using default values", FILE_NAME, e);
            apply(JsonParser.parseString(DEFAULT_FILE).getAsJsonObject());
        }
    }

    static void setItemRule(JsonObject root, String id, int required) {
        JsonArray items = root.getAsJsonArray("items");
        if (items == null) {
            items = new JsonArray();
            root.add("items", items);
        }
        // The last matching entry wins in parse(), including shorthand minecraft IDs.
        for (int i = items.size() - 1; i >= 0; i--) {
            JsonObject entry = items.get(i).getAsJsonObject();
            if (entry.has("id") && ResourceLocation.tryParse(id).equals(
                    ResourceLocation.tryParse(entry.get("id").getAsString()))) {
                entry.addProperty("required", required);
                return;
            }
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("required", required);
        items.add(entry);
    }

    static void update(Consumer<JsonObject> edit) throws IOException {
        Path path = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        JsonObject root = JsonParser.parseString(Files.exists(path) ? Files.readString(path) : DEFAULT_FILE)
                .getAsJsonObject();
        edit.accept(root);
        Data next = parse(root);
        boolean nextDebug = readDebug(root);
        Path temp = Files.createTempFile(path.getParent(), FILE_NAME, ".tmp");
        try {
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n");
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        current = next;
        debugFlow = nextDebug;
    }

    private static boolean readDebug(JsonObject root) {
        return root.has("debugFlow") && root.get("debugFlow").getAsBoolean();
    }

    private static void apply(JsonObject root) {
        current = parse(root);
        debugFlow = readDebug(root);
    }

    private static Data parse(JsonObject root) {
        int def = Math.max(1, root.has("defaultRequired") ? root.get("defaultRequired").getAsInt() : 64);
        int nonStackable = root.has("nonStackableRequired") ? root.get("nonStackableRequired").getAsInt() : 1;
        boolean condenser = !root.has("condenserRequiresLearned")
                || root.get("condenserRequiresLearned").getAsBoolean();
        // по умолчанию выключено (если ключа нет в старом конфиге - тоже false)
        boolean noEmc = root.has("noEmcWhileLearning") && root.get("noEmcWhileLearning").getAsBoolean();

        Map<String, Integer> items = new LinkedHashMap<>();
        if (root.has("items")) {
            for (JsonElement el : root.getAsJsonArray("items")) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("id")) continue;
                ResourceLocation id = ResourceLocation.tryParse(o.get("id").getAsString());
                if (id == null) {
                    LOGGER.warn("Неверный id предмета в конфиге: {}", o.get("id"));
                    continue;
                }
                items.put(id.toString(), Math.max(1, o.has("required") ? o.get("required").getAsInt() : def));
            }
        }

        List<TagRule> tags = new ArrayList<>();
        if (root.has("tags")) {
            for (JsonElement el : root.getAsJsonArray("tags")) {
                JsonObject o = el.getAsJsonObject();
                if (!o.has("tag")) continue;
                String tag = o.get("tag").getAsString();
                if (tag.startsWith("#")) tag = tag.substring(1); // можно писать и "#forge:tools"
                tags.add(new TagRule(tag, Math.max(1, o.has("required") ? o.get("required").getAsInt() : def)));
            }
        }
        return new Data(def, nonStackable, items, tags, condenser, noEmc);
    }
}
