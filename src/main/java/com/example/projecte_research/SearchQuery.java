package com.example.projecte_research;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Разбор поискового запроса в стиле JEI: @мод #подсказка $тег &id -исключить а|б "фраза". */
public final class SearchQuery {
    private record Alt(char prefix, String text) {}
    private record Token(boolean negate, List<Alt> alts) {}

    private final List<Token> tokens;

    private SearchQuery(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static SearchQuery parse(String input) {
        List<Token> tokens = new ArrayList<>();
        for (String word : splitWords(input)) {
            boolean negate = false;
            if (word.startsWith("-") && word.length() > 1) {
                negate = true;
                word = word.substring(1);
            }
            List<Alt> alts = new ArrayList<>();
            for (String part : word.split("\\|")) {
                part = part.trim().toLowerCase(Locale.ROOT);
                if (part.isEmpty()) continue;
                char c = part.charAt(0);
                if (c == '@' || c == '#' || c == '$' || c == '&') {
                    alts.add(new Alt(c, part.substring(1)));
                } else {
                    alts.add(new Alt((char) 0, part));
                }
            }
            if (!alts.isEmpty()) {
                tokens.add(new Token(negate, alts));
            }
        }
        return new SearchQuery(tokens);
    }

    /** Делит запрос на слова по пробелам, текст в кавычках остаётся одним словом. */
    private static List<String> splitWords(String input) {
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : input.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ' ' && !quoted) {
                if (cur.length() > 0) {
                    words.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            words.add(cur.toString());
        }
        return words;
    }

    public boolean matches(ItemStack stack) {
        for (Token token : tokens) {
            boolean any = false;
            for (Alt alt : token.alts()) {
                if (matchAlt(stack, alt)) {
                    any = true;
                    break;
                }
            }
            if (any == token.negate()) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchAlt(ItemStack stack, Alt alt) {
        String text = alt.text();
        if (text.isEmpty()) {
            return true; // одиночный "@" или "#" ничего не отсеивает
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        switch (alt.prefix()) {
            case '@': {
                String ns = id == null ? "" : id.getNamespace();
                return ns.toLowerCase(Locale.ROOT).contains(text)
                        || modName(ns).toLowerCase(Locale.ROOT).contains(text);
            }
            case '#': {
                for (Component line : stack.getTooltipLines(Minecraft.getInstance().player, TooltipFlag.Default.NORMAL)) {
                    if (line.getString().toLowerCase(Locale.ROOT).contains(text)) {
                        return true;
                    }
                }
                return false;
            }
            case '$':
                return stack.getTags().anyMatch(tag -> tag.location().toString().toLowerCase(Locale.ROOT).contains(text));
            case '&':
                return id != null && id.toString().toLowerCase(Locale.ROOT).contains(text);
            default:
                return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(text);
        }
    }

    private static String modName(String namespace) {
        return ModList.get().getModContainerById(namespace)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(namespace);
    }
}