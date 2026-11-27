package com.example.projecte_research;

import com.mojang.brigadier.CommandDispatcher;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import moze_intel.projecte.utils.EMCHelper;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.util.function.Consumer;

public final class AddonCommands {
    private static final int MAX_REQUIRED = 100_000_000;
    private static final SimpleCommandExceptionType NO_ITEM = new SimpleCommandExceptionType(
            Component.translatable("commands.projecte_research.error.no_item"));
    private static final SimpleCommandExceptionType NO_EMC = new SimpleCommandExceptionType(
            Component.translatable("commands.projecte_research.error.no_emc"));

    private AddonCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("projecte_research")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("config")
                        .executes(AddonCommands::showConfig)
                        .then(requiredSetting("defaultRequired"))
                        .then(requiredSetting("nonStackableRequired"))
                        .then(booleanSetting("condenserRequiresLearned"))
                        .then(booleanSetting("noEmcWhileLearning")))
                .then(Commands.literal("requirement")
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_REQUIRED))
                                        .executes(AddonCommands::setItemRequirement))))
                .then(Commands.literal("set")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0))
                                        .executes(ctx -> set(ctx, null))
                                        .then(Commands.argument("item", ItemArgument.item(context))
                                                .executes(ctx -> set(ctx, ItemArgument.getItem(ctx, "item").getItem()))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("all")
                                        .executes(AddonCommands::clearAll))
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .executes(ctx -> clearItem(ctx, ItemArgument.getItem(ctx, "item").getItem())))))
                .then(Commands.literal("reload")
                        .executes(AddonCommands::reload)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> requiredSetting(String key) {
        return Commands.literal(key).then(Commands.argument("value", IntegerArgumentType.integer(1, MAX_REQUIRED))
                .executes(ctx -> {
                    int value = IntegerArgumentType.getInteger(ctx, "value");
                    return updateConfig(ctx, key, value, root -> root.addProperty(key, value));
                }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> booleanSetting(String key) {
        return Commands.literal(key).then(Commands.argument("value", BoolArgumentType.bool())
                .executes(ctx -> {
                    boolean value = BoolArgumentType.getBool(ctx, "value");
                    return updateConfig(ctx, key, value, root -> root.addProperty(key, value));
                }));
    }

    private static int showConfig(CommandContext<CommandSourceStack> ctx) {
        var config = ResearchConfig.current();
        ctx.getSource().sendSuccess(() -> Component.literal("defaultRequired = " + config.defaultRequired()
                + "\nnonStackableRequired = " + config.nonStackableRequired()
                + "\ncondenserRequiresLearned = " + config.condenserRequiresLearned()
                + "\nnoEmcWhileLearning = " + config.noEmcWhileLearning()), false);
        return 1;
    }

    private static int setItemRequirement(CommandContext<CommandSourceStack> ctx) {
        String id = ContributionManager.keyOf(ItemArgument.getItem(ctx, "item").getItem());
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        return updateConfig(ctx, id, amount, root -> ResearchConfig.setItemRule(root, id, amount));
    }

    private static int updateConfig(CommandContext<CommandSourceStack> ctx, String key, Object value,
                                    Consumer<JsonObject> edit) {
        try {
            ResearchConfig.update(edit);
        } catch (IOException | RuntimeException e) {
            ctx.getSource().sendFailure(Component.literal("projecte_research.json: " + e.getMessage()));
            return 0;
        }
        syncPlayers(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(key + " = " + value), true);
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, Item itemArg) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        final Item item;
        if (itemArg != null) {
            item = itemArg;
        } else {
            ItemStack held = player.getMainHandItem();
            if (held.isEmpty()) throw NO_ITEM.create();
            item = held.getItem();
        }
        if (!EMCHelper.doesItemHaveEmc(new ItemStack(item))) throw NO_EMC.create();

        int required = ResearchConfig.current().requiredFor(item);
        boolean learned = ContributionManager.setProgress(player, item, amount);

        Component name = new ItemStack(item).getHoverName();
        ctx.getSource().sendSuccess(() -> learned
                ? Component.translatable("commands.projecte_research.set.learned",
                player.getDisplayName(), name, required)
                : Component.translatable("commands.projecte_research.set.progress",
                player.getDisplayName(), name, amount, required), true);
        return 1;
    }

    private static int clearAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        ContributionManager.clearAll(player);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.projecte_research.clear.all", player.getDisplayName()), true);
        return 1;
    }

    private static int clearItem(CommandContext<CommandSourceStack> ctx, Item item) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        ContributionManager.clearItem(player, item);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.projecte_research.clear.item",
                player.getDisplayName(), new ItemStack(item).getHoverName()), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ResearchConfig.load();
        syncPlayers(ctx);
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.projecte_research.reload"), true);
        return 1;
    }

    private static void syncPlayers(CommandContext<CommandSourceStack> ctx) {
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            ContributionManager.sync(p);
        }
    }
}
