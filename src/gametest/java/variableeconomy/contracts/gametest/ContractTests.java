package variableeconomy.contracts.gametest;

import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.menu.ContractBoardMenu;
import variableeconomy.contracts.block.ContractBlocks;
import variableeconomy.contracts.ContractSettings;
import variableeconomy.contracts.ContractsConfig;
import variableeconomy.contracts.ContractsModule;
import variableeconomy.contracts.api.event.ContractEvent;
import variableeconomy.contracts.compat.MarketBridge;
import variableeconomy.contracts.service.ContractService;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.Reason;
import variableeconomy.core.data.OwedPayments;
import variableeconomy.core.service.CoreServices;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static variableeconomy.contracts.gametest.CoreTestSupport.*;
import static variableeconomy.contracts.gametest.ContractTestSupport.*;

/**
 * The contract board without the market, its block, owed payouts and its events.
 *
 * Shared setup is in {@link variableeconomy.contracts.gametest.CoreTestSupport}.
 */
@GameTestHolder(ContractsModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContractTests {
    private ContractTests() {
    }


    /** Contract terms are longs: a reward above an int is offered, and only one too large to count isn't. */
    @GameTest(template = EMPTY)
    public static void offersAboveAnIntAreMade(GameTestHelper helper) {
        java.math.BigDecimal unit = java.math.BigDecimal.valueOf(2_000_000_000L);
        java.util.function.LongUnaryOperator fifth = r -> variableeconomy.contracts.service.ContractPricing.deposit(r,
                ContractSettings.DepositMode.PERCENT, 20);
        var offer = variableeconomy.contracts.service.ContractPricing.offer(true, 3, unit, java.util.OptionalLong.empty(),
                java.util.OptionalLong.of(1), fifth);
        helper.assertTrue(offer.isPresent(), "offered");
        helper.assertValueEqual(offer.get().reward(), 6_000_000_000L, "the whole reward");
        helper.assertValueEqual(offer.get().deposit(), 1_200_000_000L, "a fifth of it as deposit");
        var tooLarge = variableeconomy.contracts.service.ContractPricing.offer(true, 3,
                java.math.BigDecimal.valueOf(Long.MAX_VALUE), java.util.OptionalLong.empty(), java.util.OptionalLong.of(1), fifth);
        helper.assertTrue(tooLarge.isEmpty(), "a reward too large to count isn't offered");
        helper.succeed();
    }

    /** After a payment fails, owed money waits before it's tried again, rather than asking the bank every tick. */
    @GameTest(template = EMPTY)
    public static void owedPayoutsWaitBeforeTryingAgain(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        OwedPayments owed = OwedPayments.get(server);
        OwedPayments.Payment payment = new OwedPayments.Payment(CoreApi.accounts().personal(f.player.getUUID()), 25,
                Reason.of(ContractService.PAYOUT));
        owed.owe(payment);
        try {
            owed.failedAt(server.getTickCount());
            CoreServices.MONEY.payOwed(server);
            helper.assertTrue(owed.list().contains(payment), "not tried again yet, though the bank is back");
            helper.assertValueEqual(f.balance(), 0, "nothing paid");
            owed.retryNowForTesting();
            CoreServices.MONEY.payOwed(server);
            helper.assertFalse(owed.list().contains(payment), "paid once it's due");
            helper.assertValueEqual(f.balance(), 25, "into the player's account");
        } finally {
            owed.paid(payment);
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    /** ContractEvent's javadoc says listening for the class itself hears every contract event. */
    @GameTest(template = EMPTY)
    public static void listeningForEveryContractEventAtOnce(GameTestHelper helper) {
        try {
            registerContractEventListener();
        } catch (IllegalArgumentException e) {
            helper.fail("ContractEvent can't be listened for as a whole: " + e.getMessage());
        }
        ResourceLocation stone = ResourceLocation.withDefaultNamespace("stone");
        ContractEvent claimed = new ContractEvent.Claimed(helper.getLevel().getServer(), 1, stone, UUID.randomUUID(), stone, 1, 0, 0L);
        ContractEvent expired = new ContractEvent.Expired(helper.getLevel().getServer(), 2, stone, UUID.randomUUID(), stone, 1, 0, 0L, 0L, 0L);
        heardByParent.clear();
        hearingParent = true;
        try {
            NeoForge.EVENT_BUS.post(claimed);
            NeoForge.EVENT_BUS.post(expired);
        } finally {
            hearingParent = false;
        }
        helper.assertTrue(heardByParent.equals(List.of(claimed, expired)),
                "a listener for ContractEvent hears its nested events; heard " + heardByParent);
        heardByParent.clear();
        helper.succeed();
    }

    private static boolean contractListener;
    /** What the ContractEvent listener heard while {@link #hearingParent} was set. */
    private static final List<ContractEvent> heardByParent = new ArrayList<>();
    private static boolean hearingParent;

    private static void registerContractEventListener() {
        if (!contractListener) {
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ContractEvent.class, event -> {
                if (hearingParent) {
                    heardByParent.add(event);
                }
            });
            contractListener = true;
        }
    }

    // ---- The Contract Board: its block, access mode, ids and commands ------------------------------------------------------

    private static EconomyBlock board() {
        return new EconomyBlock("contract_board", ContractBlocks.CONTRACT_BOARD.get(), ContractBlocks.CONTRACT_BOARD_ITEM.get(), ContractBoardMenu::forBlock, ContractBoardMenu::forCommand,
                net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE);
    }

    @GameTest(template = EMPTY)
    public static void blockOnlyModeKeepsCommandsForOperators(GameTestHelper helper) {
        helper.assertTrue(ContractSettings.commandsAllowed(false, false), "everyone, by default");
        helper.assertTrue(!ContractSettings.commandsAllowed(true, false), "players need the block");
        helper.assertTrue(ContractSettings.commandsAllowed(true, true), "operators keep commands");
        helper.assertTrue(ContractSettings.blockOnly() == (ContractsConfig.accessMode() == ContractsConfig.AccessMode.BLOCK_ONLY),
                "the board reads its own access_mode");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardScreenClosesWhenYouWalkAwayOrItsBlockBreaks(GameTestHelper helper) {
        assertScreenFollowsTheBlock(helper, board());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardHasARecipeAndFacing(GameTestHelper helper) {
        assertRecipeAndFacing(helper, board());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardIsSolidAndDropsItself(GameTestHelper helper) {
        assertSolidAndDropsItself(helper, board());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void commandsSitUnderTheirOwnRoot(GameTestHelper helper) {
        for (String command : List.of("contracts", "contracts list", "contracts admin", "contracts admin refresh", "contracts admin definitions",
                "contracts admin item")) {
            helper.assertTrue(commandExists(helper, command), "/" + command + " exists");
        }
        for (String command : List.of("market contracts", "market admin contracts")) {
            helper.assertTrue(!commandExists(helper, command), "/" + command + " is gone");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everythingUsesTheModId(GameTestHelper helper) {
        assertOwnNamespace(helper, ContractsModule.MOD_ID, List.of("money.variable_economy_contracts.bits"));
        helper.assertTrue(ContractBoard.ID.startsWith("variable_economy_"), "saved data " + ContractBoard.ID + " is named after the mod");
        helper.succeed();
    }

    // ---- Without the market --------------------------------------------------------------------------------------

    /**
     * The built-in definitions: fixed-payout vanilla ones without the market, class ones with it, chosen by their
     * {@code neoforge:conditions}. Without the market the board fills from the vanilla ones.
     */
    @GameTest(template = EMPTY)
    public static void builtInDefinitionsFollowTheMarket(GameTestHelper helper) {
        var definitions = variableeconomy.contracts.definition.ContractDefinitions.INSTANCE.all();
        long vanilla = definitions.keySet().stream().filter(id -> id.getPath().startsWith("vanilla/")).count();
        long market = definitions.keySet().stream().filter(id -> id.getPath().startsWith("market/")).count();
        if (MarketBridge.available()) {
            helper.assertValueEqual(vanilla, 0L, "with the market, the vanilla definitions don't load");
            helper.assertValueEqual(market, 6L, "and one per built-in class does");
            helper.succeed();
            return;
        }
        helper.assertValueEqual(market, 0L, "without the market, the class definitions don't load");
        helper.assertValueEqual(vanilla, 17L, "and the vanilla ones do");
        helper.assertTrue(definitions.values().stream().noneMatch(variableeconomy.contracts.definition.ContractDefinition::needsMarket),
                "none of which needs the market");
        MinecraftServer server = helper.getLevel().getServer();
        ContractBoard board = new ContractBoard();
        board.observe(ContractBoard.worldDay(server));
        ContractService.refresh(server, board, net.minecraft.util.RandomSource.create(7));
        helper.assertValueEqual(board.contracts().size(), ContractSettings.boardSize(), "a full board from fixed payouts");
        helper.assertTrue(board.contracts().stream().allMatch(c -> c.definition().getPath().startsWith("vanilla/")), "all built-in");
        List<String> output = runCommand(helper, "contracts admin item minecraft:cobblestone");
        helper.assertTrue(output.stream().anyMatch(line -> line.contains("vanilla/cobblestone can offer it, paying 1.25 per unit")),
                "the admin command names the definition: " + output);
        helper.succeed();
    }

    /** Vanilla keeps a screen open through death; the board ignores a dead player's requests, so no deposit is taken. */
    @GameTest(template = EMPTY)
    public static void aDeadPlayersBoardRequestsAreIgnored(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        f.deposit(100_000);
        f.player.setHealth(0);
        f.player.containerMenu = ContractBoardMenu.forCommand();
        try {
            for (ContractBoard.Contract contract : List.copyOf(ContractBoard.get(server).contracts())) {
                variableeconomy.contracts.network.ContractSync.handleContractRequest(new variableeconomy.contracts.network.ContractPayloads
                        .ContractRequest(ContractService.Action.CLAIM, contract.id(), 0), f.player);
            }
        } finally {
            f.player.containerMenu = f.player.inventoryMenu;
        }
        helper.assertValueEqual(f.balance(), 100_000, "no deposit taken");
        helper.assertValueEqual(ContractBoard.get(server).claimedBy(f.player.getUUID()), 0, "nothing claimed");
        f.account().deduct(f.balance());
        helper.succeed();
    }
}
