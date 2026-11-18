package variableeconomy.contracts.command;

import variableeconomy.core.api.CoreApi;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import java.util.Optional;
import variableeconomy.contracts.ContractSettings;
import variableeconomy.contracts.menu.ContractBoardMenu;
import com.mojang.brigadier.CommandDispatcher;
import variableeconomy.contracts.network.ContractSync;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.service.ContractService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * {@code /contracts [list|claim|deliver|abandon]}: the contract board screen as commands, for scripting and testing without
 * the screen. {@code /contracts admin [refresh]} shows the whole board and its money counts, or replaces it now.
 */
public final class ContractCommands {
    private ContractCommands() {
    }

    /**
     * Registers {@code /contracts}: bare, it opens the board; its subcommands follow the access mode, and
     * {@code /contracts admin} needs operator permission.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("contracts")
                .executes(ContractCommands::open)
                .then(Commands.literal("list")
                        .requires(ContractSettings::commandsAllowed)
                        .executes(ContractCommands::list))
                .then(Commands.literal("claim")
                        .requires(ContractSettings::commandsAllowed)
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> report(ctx, ContractService.claim(ctx.getSource().getPlayerOrException(), id(ctx))))))
                .then(Commands.literal("deliver")
                        .requires(ContractSettings::commandsAllowed)
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> report(ctx, ContractService.deliver(ctx.getSource().getPlayerOrException(), id(ctx),
                                                IntegerArgumentType.getInteger(ctx, "qty")))))))
                .then(Commands.literal("abandon")
                        .requires(ContractSettings::commandsAllowed)
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> report(ctx, ContractService.abandon(ctx.getSource().getPlayerOrException(), id(ctx))))))
                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ContractCommands::adminInfo)
                        .then(Commands.literal("refresh").executes(ContractCommands::refresh))
                        .then(Commands.literal("definitions")
                                .executes(ctx -> definitions(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> definitions(ctx, IntegerArgumentType.getInteger(ctx, "page")))))
                        .then(Commands.literal("item")
                                .then(Commands.argument("item", ItemArgument.item(context)).executes(ContractCommands::itemInfo)))));
    }

    /** Opens the board screen; in block-only mode only operators may, and everyone else is told where to go. */
    private static int open(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!ContractSettings.commandsAllowed(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.translatable("message.variable_economy_contracts.block_only.contracts"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ContractBoardMenu.open(player);
        ContractSync.sendFullState(player);
        return 1;
    }

    /**
     * Which contract definitions ask for an item, and for each what a contract pays per unit before the cap, or why it
     * can't offer one. Returns how many can.
     */
    private static int itemInfo(CommandContext<CommandSourceStack> ctx) {
        Item item = ItemArgument.getItem(ctx, "item").getItem();
        MinecraftServer server = ctx.getSource().getServer();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        List<String> lines = ContractService.explain(server, item);
        if (lines.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("%s is never offered: no contract definition asks for it".formatted(id)), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(id + ":"), false);
        lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal("  " + line), false));
        return (int) lines.stream().filter(line -> line.contains(" can offer it")).count();
    }

    private static final int DEFINITIONS_PER_PAGE = 8;

    /**
     * Every contract definition with its terms, where it came from and whether it can offer contracts now, ten a page;
     * then the files the last load skipped and the diagnostics' warnings.
     */
    private static int definitions(CommandContext<CommandSourceStack> ctx, int page) {
        MinecraftServer server = ctx.getSource().getServer();
        List<variableeconomy.contracts.definition.ContractDefinition> all =
                List.copyOf(variableeconomy.contracts.definition.ContractDefinitions.INSTANCE.all().values());
        List<String> warnings = variableeconomy.contracts.service.ContractDiagnostics.run(server, all);
        List<String> skipped = variableeconomy.contracts.definition.ContractDefinitions.INSTANCE.problems();
        int pages = Math.max(1, (all.size() + DEFINITIONS_PER_PAGE - 1) / DEFINITIONS_PER_PAGE);
        int shown = Math.min(page, pages);
        ctx.getSource().sendSuccess(() -> Component.literal("%d contract definition(s), %d skipped, %d warning(s); page %d of %d"
                .formatted(all.size(), skipped.size(), warnings.size(), shown, pages)), false);
        all.stream().skip((long) (shown - 1) * DEFINITIONS_PER_PAGE).limit(DEFINITIONS_PER_PAGE).forEach(d -> {
            String state = d.weight() == 0 ? "off (weight 0)"
                    : d.needsMarket() && !variableeconomy.contracts.compat.MarketBridge.available() ? "off (needs the market)"
                    : "%d item(s) can be offered".formatted(ContractService.items(server, d).stream()
                    .filter(item -> ContractService.whyNotOffered(server, d, item, d.minQuantity(),
                            variableeconomy.contracts.service.ContractPricing::deposit).isEmpty()).count());
            ctx.getSource().sendSuccess(() -> Component.literal("  %s: %s, %s units, %s, weight %d%s; %s [%s]".formatted(d.id(),
                    d.target().describe(), d.minQuantity() == d.maxQuantity() ? String.valueOf(d.minQuantity()) : d.minQuantity() + "–" + d.maxQuantity(),
                    d.payout().describe(), d.weight(), d.stage().map(s -> ", needs stage " + s).orElse(""), state, d.source())), false);
        });
        if (shown < pages) {
            ctx.getSource().sendSuccess(() -> Component.literal("/contracts admin definitions " + (shown + 1) + " for more"), false);
        }
        skipped.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal("[skipped] " + line)
                .withStyle(net.minecraft.ChatFormatting.RED), false));
        warnings.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal("[warning] " + line)
                .withStyle(net.minecraft.ChatFormatting.YELLOW), false));
        return all.size();
    }

    private static int id(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "id");
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!ContractSettings.contractsEnabled()) {
            ctx.getSource().sendFailure(Component.literal("Contracts are turned off on this server"));
            return 0;
        }
        ContractBoard board = ContractBoard.get(player.server);
        List<ContractService.View> views = ContractService.view(player, board);
        String unit = "bits";
        ctx.getSource().sendSuccess(() -> Component.literal("Contracts (new board in %d day(s)); you hold %d of %d:".formatted(
                board.daysLeft(), board.claimedBy(player.getUUID()), ContractSettings.maxClaims())), false);
        for (ContractService.View view : views) {
            ContractBoard.Contract c = view.contract();
            String state = switch (c.status()) {
                case OPEN -> "open";
                case CLAIMED -> view.mine() ? "yours, %d/%d delivered, %d day(s) left".formatted(c.delivered(), c.units(), view.daysLeft())
                        : "claimed by " + view.holderName();
                case COMPLETED -> "done";
                case ABANDONED -> "abandoned";
            };
            String lock = view.itemLocked() ? " [locked: you haven't unlocked this item]"
                    : view.missingStage().map(stage -> " [locked: needs stage " + stage + "]").orElse("");
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: %d × %s pays %d %s, deposit %d (%s)%s".formatted(c.id(), c.units(),
                    c.item(), view.reward(), unit, c.deposit(), state, lock)), false);
        }
        if (views.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  none right now"), false);
        }
        return views.size();
    }

    private static int report(CommandContext<CommandSourceStack> ctx, ContractService.Outcome outcome) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!outcome.ok()) {
            ctx.getSource().sendFailure(Component.literal("Contract %s failed: %s".formatted(outcome.action().name().toLowerCase(Locale.ROOT),
                    outcome.result())));
            return 0;
        }
        String unit = "bits";
        String message = switch (outcome.action()) {
            case CLAIM -> "Claimed the %s contract; %d %s deposit taken".formatted(outcome.item(), outcome.amount(), unit);
            case DELIVER -> outcome.amount() > 0
                    ? "Delivered %d and finished the %s contract: paid %d %s, deposit included".formatted(outcome.quantity(), outcome.item(),
                    outcome.amount(), unit)
                    : "Delivered %d × %s".formatted(outcome.quantity(), outcome.item());
            case ABANDON -> "Abandoned the %s contract with %d delivered: paid %d %s, part of the deposit included".formatted(outcome.item(),
                    outcome.quantity(), outcome.amount(), unit);
        };
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        ContractSync.broadcastContracts(player.server);
        return 1;
    }

    private static int adminInfo(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ContractBoard board = ContractBoard.get(server);
        String unit = "bits";
        ctx.getSource().sendSuccess(() -> Component.literal(("Contract board: day %d, %d contract(s), replaced on day %d. Contracts are %s. "
                + "Paid out so far: %d %s; deposits kept: %d %s").formatted(board.day(), board.contracts().size(), board.expiresDay(),
                ContractSettings.contractsEnabled() ? "on" : "off", board.rewardsPaid(), unit, board.depositsLost(), unit)), false);
        for (ContractBoard.Contract c : board.contracts()) {
            String holder = c.holder().map(id -> ContractService.name(server, id).orElse(id.toString())).orElse("-");
            String until = c.status() == ContractBoard.Status.CLAIMED ? ", until day " + c.deadline() : "";
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d %s: %d/%d × %s, pays %d (offered %d), deposit %d, holder %s%s".formatted(
                    c.id(), c.status().name().toLowerCase(Locale.ROOT), c.delivered(), c.units(), c.item(),
                    ContractService.payout(server, c, c.units()), c.reward(), c.deposit(), holder, until)), false);
        }
        return board.contracts().size();
    }

    private static int refresh(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (!ContractSettings.contractsEnabled()) {
            ctx.getSource().sendFailure(Component.literal("Contracts are turned off (contracts.enabled in variable_economy_contracts-server.toml)"));
            return 0;
        }
        ContractBoard board = ContractBoard.get(server);
        int before = board.contracts().size();
        ContractService.refresh(server, board, server.overworld().getRandom());
        ContractSync.broadcastContracts(server);
        CoreApi.audit().record(ctx.getSource(), "contracts refresh", "board", "ok", new JsonPrimitive(before),
                new JsonPrimitive(board.contracts().size()));
        String items = board.contracts().stream().map(c -> BuiltInRegistries.ITEM.get(c.item()).getDescription().getString())
                .reduce((a, b) -> a + ", " + b).orElse("nothing qualifies");
        ctx.getSource().sendSuccess(() -> Component.literal("Replaced the contract board (held contracts stay until they're done or their time runs out). "
                + "New board until day %d: %s".formatted(board.expiresDay(), items)), true);
        return board.contracts().size();
    }
}
