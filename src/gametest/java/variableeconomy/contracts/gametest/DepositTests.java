package variableeconomy.contracts.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.contracts.ContractSettings;
import variableeconomy.contracts.ContractsConfig;
import variableeconomy.contracts.ContractsModule;
import variableeconomy.contracts.api.event.ContractEvent;
import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.service.ContractPricing;
import variableeconomy.contracts.service.ContractResult;
import variableeconomy.contracts.service.ContractService;
import variableeconomy.core.api.CoreApi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static variableeconomy.contracts.gametest.ContractTestSupport.*;
import static variableeconomy.contracts.gametest.CoreTestSupport.*;

/**
 * Claims, deposits, time limits and deliveries, on boards of the tests' own. The contracts are for barriers, which no
 * market lists, so their payouts aren't capped and the tests run the same with or without the market.
 */
@GameTestHolder(ContractsModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DepositTests {
    private DepositTests() {
    }

    @GameTest(template = EMPTY)
    public static void depositsAndRefundsAreWorkedOutExactly(GameTestHelper helper) {
        helper.assertValueEqual(ContractPricing.deposit(1000, ContractSettings.DepositMode.PERCENT, 20), 200L, "a fifth");
        helper.assertValueEqual(ContractPricing.deposit(7, ContractSettings.DepositMode.PERCENT, 20), 2L, "rounded up from 1.4");
        helper.assertValueEqual(ContractPricing.deposit(7, ContractSettings.DepositMode.PERCENT, 0), 0L, "none");
        helper.assertValueEqual(ContractPricing.deposit(1000, ContractSettings.DepositMode.FLAT, 50.9), 50L, "flat, fraction dropped");
        helper.assertValueEqual(ContractPricing.deposit(Long.MAX_VALUE, ContractSettings.DepositMode.PERCENT, 200), Long.MAX_VALUE,
                "too large to count");
        helper.assertValueEqual(ContractPricing.refund(33, 50), 16L, "half, rounded down");
        helper.assertValueEqual(ContractPricing.refund(33, 0), 0L, "nothing back");
        helper.assertValueEqual(ContractPricing.refund(33, 100), 33L, "all of it");
        helper.assertValueEqual(ContractPricing.refund(0, 50), 0L, "no deposit, nothing back");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void claimLimitAndDeadline(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(barriers(b, 10, 2, 4), barriers(b, 10, 2, 4), barriers(b, 10, 2, 4)));
        List<Integer> ids = board.contracts().stream().map(ContractBoard.Contract::id).toList();
        withSetting(ContractsConfig.MAX_CLAIMS, 2, () -> withSetting(ContractsConfig.DURATION_DAYS, 3, () -> {
            helper.assertValueEqual(ContractService.claim(f.player, board, ids.get(0)).result(), ContractResult.OK, "first");
            helper.assertValueEqual(ContractService.claim(f.player, board, ids.get(1)).result(), ContractResult.OK, "second");
            helper.assertValueEqual(ContractService.claim(f.player, board, ids.get(2)).result(), ContractResult.CONTRACT_LIMIT, "third");
            helper.assertValueEqual(f.balance(), 1000 - 8, "two deposits taken");
            helper.assertValueEqual(contract(board, ids.get(0)).deadline(), board.day() + 3, "three days to deliver");
            helper.assertValueEqual(contract(board, ids.get(0)).account(), Optional.of(f.player.getUUID()),
                    "the paying account is kept, the player's own included");
        }));
        f.account().deduct(f.balance());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aHeldContractOutlivesItsBoardAndExpiresOnItsOwnDay(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(barriers(b, 10, 2, 40), barriers(b, 10, 2, 40)));
        int held = board.contracts().get(0).id();
        long today = board.day();
        withSetting(ContractsConfig.DURATION_DAYS, 7, () -> withSetting(ContractsConfig.REFUND_ON_EXPIRY, 25.0, () -> {
            helper.assertValueEqual(ContractService.claim(f.player, board, held).result(), ContractResult.OK, "claimed");
            f.giveMany(Items.BARRIER, 4);
            ContractService.deliver(f.player, board, held, 4);
            board.observe(ContractBoard.worldDay(server) + 5);
            helper.assertTrue(board.due(), "the board's day has come");
            ContractService.refresh(server, board, net.minecraft.util.RandomSource.create(3));
            helper.assertValueEqual(contract(board, held).status(), ContractBoard.Status.CLAIMED, "the held contract stays on the new board");
            helper.assertValueEqual(board.contracts().stream().filter(c -> c.status() == ContractBoard.Status.OPEN
                    && c.item().equals(id(Items.BARRIER))).count(), 0L, "the open one went with the old board");
            helper.assertValueEqual(board.daysLeft(contract(board, held)), today + 7 - board.day(), "days left counted from claiming");
            helper.assertFalse(ContractService.expireOverdue(server, board), "not yet");

            board.observe(ContractBoard.worldDay(server) + 7);
            helper.assertValueEqual(ContractService.deliver(f.player, board, held, 1).result(), ContractResult.CONTRACT_EXPIRED,
                    "once its day comes it takes nothing more, even before the tick ends it");
            List<Event> events = contractEventsDuring(() -> helper.assertTrue(ContractService.expireOverdue(server, board), "ended"));
            ContractEvent.Expired expired = only(events, ContractEvent.Expired.class).get(0);
            helper.assertValueEqual(expired.getPaid(), 8L, "4 × 2");
            helper.assertValueEqual(expired.getDepositReturned(), 10L, "a quarter of the deposit");
            helper.assertValueEqual(expired.getDepositLost(), 30L, "the rest kept");
            helper.assertTrue(board.find(held).isEmpty(), "off the board");
            helper.assertValueEqual(f.balance(), 1000 - 40 + 8 + 10, "the money matches");
        }));
        f.account().deduct(f.balance());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void abandoningReturnsTheConfiguredPartOfTheDeposit(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(barriers(b, 10, 2, 41)));
        int id = board.contracts().get(0).id();
        withSetting(ContractsConfig.REFUND_ON_ABANDON, 50.0, () -> {
            ContractService.claim(f.player, board, id);
            f.giveMany(Items.BARRIER, 3);
            ContractService.deliver(f.player, board, id, 3);
            List<Event> events = contractEventsDuring(() -> helper.assertValueEqual(ContractService.abandon(f.player, board, id).amount(),
                    6L + 20, "3 × 2, and half of 41 rounded down"));
            ContractEvent.Abandoned abandoned = only(events, ContractEvent.Abandoned.class).get(0);
            helper.assertValueEqual(abandoned.getDepositReturned(), 20L, "returned");
            helper.assertValueEqual(abandoned.getDepositLost(), 21L, "kept");
            helper.assertValueEqual(board.depositsLost(), 21L, "counted as kept");
            helper.assertValueEqual(f.balance(), 1000 - 41 + 26, "the money matches");
        });
        f.account().deduct(f.balance());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void wholeDeliveriesWhenBatchesAreOff(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(barriers(b, 10, 2, 4)));
        int id = board.contracts().get(0).id();
        withSetting(ContractsConfig.PARTIAL_ALLOWED, false, () -> {
            ContractService.claim(f.player, board, id);
            f.giveMany(Items.BARRIER, 10);
            helper.assertValueEqual(ContractService.deliver(f.player, board, id, 5).result(), ContractResult.WHOLE_DELIVERY, "half");
            helper.assertValueEqual(f.count(Items.BARRIER), 10L, "nothing taken");
            helper.assertValueEqual(ContractService.deliver(f.player, board, id, 10).result(), ContractResult.OK, "all at once");
            helper.assertValueEqual(f.balance(), 1000 + 20, "the reward, the deposit back");

            // A contract larger than one request may move can still be finished, in its one delivery.
            ContractBoard big = board(helper, b -> List.of(barriers(b, 10, 2, 4)));
            int bigId = big.contracts().get(0).id();
            ContractService.claim(f.player, big, bigId);
            f.giveMany(Items.BARRIER, 10);
            withSetting(ContractsConfig.MAX_QUANTITY_PER_REQUEST, 5, () ->
                    helper.assertValueEqual(ContractService.deliver(f.player, big, bigId, 10).result(), ContractResult.OK,
                            "above max_quantity_per_request, but the whole contract"));
        });
        f.account().deduct(f.balance());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void heldDepositsCountAsTheirAccountsMoney(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(barriers(b, 10, 2, 7), barriers(b, 10, 2, 5), barriers(b, 10, 2, 100)));
        ContractService.claim(f.player, board, board.contracts().get(0).id());
        ContractService.claim(f.player, board, board.contracts().get(1).id());
        helper.assertValueEqual(ContractService.heldDeposits(server, board), Map.of(CoreApi.accounts().personal(f.player.getUUID()), 12L),
                "both deposits, for the account that paid them; the open contract's counts for nobody");
        ContractService.settleAll(server, board);
        helper.assertValueEqual(ContractService.heldDeposits(server, board), Map.of(), "none once settled");
        helper.assertValueEqual(f.balance(), 1000, "turning contracts off returns deposits whole");
        helper.assertTrue(board.contracts().isEmpty(), "and takes the board down");
        f.account().deduct(f.balance());
        helper.succeed();
    }

    /** A board saved in the older format (version 1) still loads: its "team" is the paying account, and held contracts end with that board. */
    @GameTest(template = EMPTY)
    public static void aVersionOneBoardLoads(GameTestHelper helper) {
        CoreFixture f = new CoreFixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(barriers(b, 10, 2, 4)));
        ContractService.claim(f.player, board, board.contracts().get(0).id());
        CompoundTag saved = board.save(new CompoundTag(), helper.getLevel().registryAccess());
        ContractBoard reloaded = ContractBoard.fromTag(saved.copy(), helper.getLevel().registryAccess());
        helper.assertValueEqual(reloaded.contracts(), board.contracts(), "version 2 round-trips");

        saved.putInt("version", 1);
        CompoundTag c = ((ListTag) saved.get("contracts")).getCompound(0);
        c.putUUID("team", c.getUUID("account"));
        c.remove("account");
        c.remove("deadline");
        ContractBoard old = ContractBoard.fromTag(saved, helper.getLevel().registryAccess());
        ContractBoard.Contract contract = old.contracts().get(0);
        helper.assertValueEqual(contract.account(), Optional.of(f.player.getUUID()), "team read as the account");
        helper.assertValueEqual(contract.deadline(), board.expiresDay(), "ends with the board it was on");
        helper.assertTrue(saved.getList("contracts", Tag.TAG_COMPOUND).size() == 1, "one contract");
        f.account().deduct(f.balance());
        helper.succeed();
    }

    /** An open contract for {@code units} barriers paying {@code perUnit} each, with this deposit. */
    private static ContractBoard.Contract barriers(ContractBoard board, int units, long perUnit, long deposit) {
        return new ContractBoard.Contract(board.takeId(), rl("test_barriers"), id(Items.BARRIER), units, BigDecimal.valueOf(perUnit), units * perUnit,
                deposit, 0, Optional.empty(), Optional.empty(), -1, ContractBoard.Status.OPEN);
    }
}
