package com.example.projecte_research;

import com.mojang.brigadier.CommandDispatcher;
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

public final class AddonCommands {
    private static final SimpleCommandExceptionType NO_ITEM = new SimpleCommandExceptionType(
            Component.translatable("commands.projecte_research.error.no_item"));
    private static final SimpleCommandExceptionType NO_EMC = new SimpleCommandExceptionType(
            Component.translatable("commands.projecte_research.error.no_emc"));

    private AddonCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("projecte_research")
                .requires(src -> src.hasPermission(2))
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
        for (ServerPlayer p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            ContributionManager.sync(p);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.projecte_research.reload"), true);
        return 1;
    }
}