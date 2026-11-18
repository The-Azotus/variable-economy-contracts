package variableeconomy.contracts.service;

import variableeconomy.contracts.ContractSettings;
import variableeconomy.contracts.ContractsModule;
import variableeconomy.contracts.compat.MarketBridge;
import variableeconomy.contracts.definition.ContractDefinition;
import variableeconomy.contracts.definition.ContractDefinitions;
import com.mojang.authlib.GameProfile;
import variableeconomy.contracts.api.event.ContractEvent;
import variableeconomy.core.api.AccountId;
import variableeconomy.core.api.Accounts;
import variableeconomy.core.api.BankResult;
import variableeconomy.core.api.CoreApi;
import variableeconomy.core.api.PaymentResult;
import variableeconomy.core.api.PlayerItems;
import variableeconomy.core.api.Reason;
import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.data.ContractBoard.Contract;
import variableeconomy.contracts.data.ContractBoard.Status;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.OptionalLong;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Predicate;

/**
 * The only code that changes the contract board or moves contract money. Money moves only through core's
 * {@link variableeconomy.core.api.Money}, following the market's rules: every check runs before anything changes, on the server thread, and
 * once the first change is made no remaining step can fail.
 * <ul>
 *   <li>Claiming takes the deposit from the account the player trades with and locks the contract to them for
 *       {@code contract.duration_days}. A held contract stays when the board is replaced, until it's done or its
 *       time runs out.</li>
 *   <li>Units are delivered from the main inventory only, in batches unless {@code delivery.partial_allowed} is off;
 *       they leave the game, never joining market stock.</li>
 *   <li>The last unit pays the reward and returns the deposit. Abandoning or running out of time pays for the units
 *       delivered and returns part of the deposit ({@code deposit.refund_on_abandon}, {@code refund_on_expiry}).
 *       Payouts go to the account the deposit came from while it exists, otherwise the holder's own.</li>
 *   <li>A payout is worked out when it's made ({@link ContractPricing#payout}), never above what was offered, so it
 *       stays below the least the market could charge for those units even if the catalog changed meanwhile.</li>
 * </ul>
 * Each action that succeeds, and each held contract that expires, posts a {@link ContractEvent} once it is complete.
 */
public final class ContractService {
    private ContractService() {
    }

    public enum Action { CLAIM, DELIVER, ABANDON }

    /** What a claim's deposit is, in core's money service. */
    public static final ResourceLocation DEPOSIT = ResourceLocation.fromNamespaceAndPath(ContractsModule.MOD_ID, "contract_deposit");
    /** What a contract's payout (and a returned deposit) is, in core's money service. */
    public static final ResourceLocation PAYOUT = ResourceLocation.fromNamespaceAndPath(ContractsModule.MOD_ID, "contract_payout");

    /**
     * How a contract action went.
     *
     * @param quantity units delivered by this request (for abandoning: units delivered in all)
     * @param amount   the deposit taken when claiming; otherwise what was paid into the bank
     */
    public record Outcome(Action action, ContractResult result, ResourceLocation item, int quantity, long amount) {
        static Outcome fail(Action action, ContractResult result, ResourceLocation item) {
            return new Outcome(action, result, item, 0, 0);
        }

        public boolean ok() {
            return result == ContractResult.OK;
        }
    }

    /**
     * A contract as one player sees it. {@code reward} is what completing it would pay now; for a contract they hold,
     * {@code daysLeft} is the days until its time runs out (otherwise -1) and {@code abandonRefund} what abandoning it
     * would pay back now: the units delivered and part of the deposit. {@code itemLocked} and {@code missingStage} say
     * why they can't claim it, if they can't.
     */
    public record View(Contract contract, long reward, String holderName, boolean mine, long daysLeft, long abandonRefund,
                       boolean itemLocked, Optional<String> missingStage) {
        /** Whether the player can't claim it: shown greyed out. */
        public boolean locked() {
            return itemLocked || missingStage.isPresent();
        }
    }

    // ---- The board -------------------------------------------------------------------------------------------

    /**
     * Every tick: advances the day count, ends held contracts whose time has run out, and replaces the board when its
     * day has come (or clears it if contracts were turned off). Returns whether the board changed, so open screens can
     * be refreshed.
     */
    public static boolean tick(MinecraftServer server) {
        ContractBoard board = ContractBoard.get(server);
        board.observe(ContractBoard.worldDay(server));
        if (!ContractSettings.contractsEnabled()) {
            if (board.expiresDay() < 0) {
                return false;
            }
            settleAll(server, board);
            return true;
        }
        boolean changed = expireOverdue(server, board);
        if (board.due()) {
            refresh(server, board, server.overworld().getRandom());
            changed = true;
        }
        return changed;
    }

    /**
     * Ends every held contract and takes the board down, as when contracts are turned off. Not the players' doing, so
     * each is paid for the units delivered and gets the whole deposit back.
     */
    public static void settleAll(MinecraftServer server, ContractBoard board) {
        List<ContractEvent.Expired> expired = settle(server, board, held(board), 100);
        board.clear();
        ContractsEvents.postAll(expired);
    }

    /**
     * Ends every held contract whose time has run out: its units delivered are paid for and {@code
     * deposit.refund_on_expiry} of its deposit returned, and it leaves the board. Returns whether any did.
     */
    public static boolean expireOverdue(MinecraftServer server, ContractBoard board) {
        List<Contract> overdue = board.overdue();
        if (overdue.isEmpty()) {
            return false;
        }
        List<ContractEvent.Expired> expired = settle(server, board, overdue, ContractSettings.refundOnExpiry());
        overdue.forEach(contract -> board.remove(contract.id()));
        ContractsEvents.postAll(expired);
        return true;
    }

    /** The contracts players hold. */
    private static List<Contract> held(ContractBoard board) {
        return board.contracts().stream().filter(c -> c.status() == Status.CLAIMED && c.holder().isPresent()).toList();
    }

    /** Puts up a new board. Contracts players hold stay on it until they're done or their time runs out. */
    public static void refresh(MinecraftServer server, ContractBoard board, RandomSource random) {
        board.replace(generate(server, random, ContractSettings.boardSize(), ContractPricing::deposit, board::takeId),
                ContractSettings.refreshDays());
        ContractsModule.LOGGER.debug("New contract board on day {}: {} contracts, until day {}", board.day(),
                board.contracts().size(), board.expiresDay());
    }

    /**
     * Up to {@code count} contracts for different items, from the contract definitions ({@link ContractDefinitions}).
     * Definitions are picked at random in proportion to their weight; each pick makes a contract for one item of its
     * pool that can be offered ({@link #offer}), at a random size between its minimum and maximum (sizes from 64 up
     * rounded down to a multiple of 16). A definition whose pool is a tag or class may be picked again for another
     * item; a single-item one gives at most one contract. Without the market, definitions that need it are left out.
     */
    public static List<Contract> generate(MinecraftServer server, RandomSource random, int count, LongUnaryOperator depositOf, IntSupplier ids) {
        return generate(server, random, count, depositOf, ids, ContractDefinitions.INSTANCE.all().values());
    }

    /** The same from these definitions. */
    public static List<Contract> generate(MinecraftServer server, RandomSource random, int count, LongUnaryOperator depositOf, IntSupplier ids,
                                          Collection<ContractDefinition> definitions) {
        List<ContractDefinition> pool = new ArrayList<>(definitions.stream()
                .filter(d -> d.weight() > 0 && (MarketBridge.available() || !d.needsMarket())).toList());
        Map<ResourceLocation, List<Item>> poolItems = new HashMap<>();
        Set<Item> used = new HashSet<>();
        long totalWeight = pool.stream().mapToLong(ContractDefinition::weight).sum();
        List<Contract> picked = new ArrayList<>();
        while (picked.size() < count && !pool.isEmpty()) {
            long target = (long) (random.nextDouble() * totalWeight);
            int index = 0;
            for (long seen = pool.get(0).weight(); seen <= target && index < pool.size() - 1; seen += pool.get(index).weight()) {
                index++;
            }
            ContractDefinition definition = pool.get(index);
            List<Item> candidates = poolItems.computeIfAbsent(definition.id(), id -> shuffled(items(server, definition), random));
            Optional<Contract> made = Optional.empty();
            while (made.isEmpty() && !candidates.isEmpty()) {
                Item item = candidates.remove(candidates.size() - 1);
                if (used.contains(item)) {
                    continue;
                }
                Optional<ContractPricing.Offer> offer = offer(server, definition, item, size(definition, random), depositOf)
                        .or(() -> offer(server, definition, item, definition.minQuantity(), depositOf));
                if (offer.isPresent()) {
                    used.add(item);
                    made = Optional.of(new Contract(ids.getAsInt(), definition.id(), BuiltInRegistries.ITEM.getKey(item), offer.get().units(),
                            offer.get().unitReward(), offer.get().reward(), offer.get().deposit(), 0, Optional.empty(), Optional.empty(), -1,
                            Status.OPEN));
                }
            }
            made.ifPresent(picked::add);
            if (made.isEmpty() || definition.singleItem() || candidates.isEmpty()) {
                pool.remove(index);
                totalWeight -= definition.weight();
            }
        }
        return picked;
    }

    /**
     * Every item a definition's pool holds: its item, the items in its tag, or the items the market gives its class.
     * Never coins or air.
     */
    public static List<Item> items(MinecraftServer server, ContractDefinition definition) {
        List<Item> items = switch (definition.target()) {
            case ContractDefinition.ItemTarget one -> List.of(one.item());
            case ContractDefinition.TagTarget tag -> BuiltInRegistries.ITEM.getTag(tag.tag())
                    .map(set -> set.stream().map(net.minecraft.core.Holder::value).toList()).orElse(List.of());
            case ContractDefinition.ClassTarget itemClass -> MarketBridge.listedItems(server).stream()
                    .filter(item -> MarketBridge.value(server, item).flatMap(MarketBridge.Value::itemClass)
                            .filter(itemClass.itemClass()::equals).isPresent())
                    .sorted(java.util.Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                    .toList();
        };
        return items.stream().filter(item -> item != Items.AIR && !CoreApi.isCoin(item)).toList();
    }

    /** Whether {@code item} is in the definition's pool. */
    public static boolean asksFor(MinecraftServer server, ContractDefinition definition, Item item) {
        if (item == Items.AIR || CoreApi.isCoin(item)) {
            return false;
        }
        return switch (definition.target()) {
            case ContractDefinition.ItemTarget one -> one.item() == item;
            case ContractDefinition.TagTarget tag -> item.builtInRegistryHolder().is(tag.tag());
            case ContractDefinition.ClassTarget itemClass -> MarketBridge.value(server, item).flatMap(MarketBridge.Value::itemClass)
                    .filter(itemClass.itemClass()::equals).isPresent();
        };
    }

    private static List<Item> shuffled(List<Item> items, RandomSource random) {
        List<Item> copy = new ArrayList<>(items);
        for (int i = copy.size() - 1; i > 0; i--) {
            java.util.Collections.swap(copy, i, random.nextInt(i + 1));
        }
        return copy;
    }

    /**
     * What a definition's contract pays per unit for {@code item} before the cap: its fixed amount, or its multiplier
     * times the item's market base price. Empty when a multiplier has nothing to multiply (no market, or the market
     * doesn't list the item).
     */
    public static Optional<BigDecimal> unitReward(MinecraftServer server, ContractDefinition definition, Item item) {
        if (definition.payout().perUnit().isPresent()) {
            return definition.payout().perUnit();
        }
        BigDecimal multiplier = definition.payout().multiplier().orElseThrow();
        return MarketBridge.value(server, item).map(value -> BigDecimal.valueOf(value.basePrice()).multiply(multiplier));
    }

    /**
     * A contract for {@code units} of {@code item} from this definition, priced from what the market reports now, or
     * empty if one can't be offered: see {@link #whyNotOffered}.
     */
    public static Optional<ContractPricing.Offer> offer(MinecraftServer server, ContractDefinition definition, Item item, int units,
                                                       LongUnaryOperator depositOf) {
        return whyNotOffered(server, definition, item, units, depositOf).isEmpty()
                ? ContractPricing.offer(true, units, unitReward(server, definition, item).orElseThrow(), cap(server, item, units),
                normalSellTotal(server, item, units), depositOf)
                : Optional.empty();
    }

    /** What selling the units to the market at base price pays; empty if the market doesn't list or buy the item. */
    private static OptionalLong normalSellTotal(MinecraftServer server, Item item, int units) {
        return MarketBridge.value(server, item).filter(MarketBridge.Value::canSell).isPresent()
                ? MarketBridge.normalSellTotal(server, item, units) : OptionalLong.empty();
    }

    /**
     * Why a definition can't offer a contract for {@code units} of {@code item}, or empty if it can. For admins, and
     * the board's own rule: a definition off (weight 0) or needing a market that isn't installed; a multiplier for an
     * item the market doesn't list; while {@code contracts.clamp_to_market} is on, an item the market doesn't buy
     * ({@code can_sell: false}), since a contract would reopen what that closed; and a contract that couldn't pay more
     * than selling those units to the market (after the cap), or is too large to count.
     */
    public static Optional<String> whyNotOffered(MinecraftServer server, ContractDefinition definition, Item item, int units,
                                                 LongUnaryOperator depositOf) {
        if (definition.weight() == 0) {
            return Optional.of("its weight is 0");
        }
        if (definition.needsMarket() && !MarketBridge.available()) {
            return Optional.of(definition.payout().multiplier().isPresent()
                    ? "it pays a multiplier on market value, and the market add-on isn't installed"
                    : "it asks for a market class, and the market add-on isn't installed");
        }
        Optional<MarketBridge.Value> value = MarketBridge.value(server, item);
        Optional<BigDecimal> unitReward = unitReward(server, definition, item);
        if (unitReward.isEmpty()) {
            return Optional.of("it pays a multiplier on market value, and the market doesn't list " + BuiltInRegistries.ITEM.getKey(item));
        }
        if (value.isPresent() && !value.get().canSell() && ContractSettings.clampToMarket()) {
            return Optional.of("the market doesn't buy it (can_sell false), so a contract would reopen what that closed");
        }
        return ContractPricing.whyNotOffered(units, unitReward.get(), cap(server, item, units), normalSellTotal(server, item, units),
                depositOf);
    }

    /**
     * The round-trip cap on what {@code units} of {@code item} may pay: less than buying them from the market could ever
     * cost. Empty when there's none: the market isn't installed, doesn't sell the item, or {@code
     * contracts.clamp_to_market} is off (the admin's choice, warned about at startup).
     */
    public static OptionalLong cap(MinecraftServer server, Item item, int units) {
        return ContractSettings.clampToMarket() ? MarketBridge.lowestBuyCost(server, item, units) : OptionalLong.empty();
    }

    /**
     * For admins: for each definition that asks for {@code item}, what its contracts pay per unit before the cap, or
     * why it can't offer one (at its smallest size). Empty if no definition asks for it.
     */
    public static List<String> explain(MinecraftServer server, Item item) {
        List<String> lines = new ArrayList<>();
        for (ContractDefinition definition : ContractDefinitions.INSTANCE.all().values()) {
            if (!asksFor(server, definition, item)) {
                continue;
            }
            Optional<String> why = whyNotOffered(server, definition, item, definition.minQuantity(), ContractPricing::deposit);
            lines.add(why.map(reason -> "%s can't offer it: %s".formatted(definition.id(), reason))
                    .orElseGet(() -> "%s can offer it, paying %s per unit before the cap (%d to %d units, weight %d)".formatted(definition.id(),
                            unitReward(server, definition, item).orElseThrow().setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                                    .toPlainString(), definition.minQuantity(), definition.maxQuantity(), definition.weight())));
        }
        return lines;
    }

    private static int size(ContractDefinition definition, RandomSource random) {
        int min = definition.minQuantity();
        int max = definition.maxQuantity();
        int units = min + random.nextInt(max - min + 1);
        return max >= 64 ? Math.max(min, units / 16 * 16) : units;
    }

    /**
     * Ends these held contracts: each holder is paid for the units delivered and gets {@code refundPercent} of the
     * deposit back. Each is told now, or when they next log in. Returns an event for each contract ended, for the
     * caller to post once the board has changed too.
     */
    static List<ContractEvent.Expired> settle(MinecraftServer server, ContractBoard board, List<Contract> ended, double refundPercent) {
        List<ContractEvent.Expired> expired = new ArrayList<>();
        for (Contract contract : ended) {
            if (contract.status() != Status.CLAIMED || contract.holder().isEmpty()) {
                continue;
            }
            long paid = payout(server, contract, contract.delivered());
            long returned = ContractPricing.refund(contract.deposit(), refundPercent);
            long lost = contract.deposit() - returned;
            payBack(server, contract.holder().get(), contract.account(), paid + returned, Reason.of(PAYOUT));
            board.count(paid, lost);
            tell(server, board, new ContractBoard.Notice(contract.holder().get(), contract.item(), contract.delivered(),
                    contract.units(), paid, lost));
            expired.add(new ContractEvent.Expired(server, contract.id(), contract.definition(), contract.holder().get(), contract.item(), contract.units(),
                    contract.delivered(), paid, returned, lost));
        }
        return expired;
    }

    /** Tells the holder how their contract ended: in chat if they're online, otherwise when they next log in. */
    private static void tell(MinecraftServer server, ContractBoard board, ContractBoard.Notice notice) {
        ServerPlayer online = server.getPlayerList().getPlayer(notice.player());
        if (online != null) {
            online.sendSystemMessage(message(server, notice));
        } else {
            board.addNotice(notice);
        }
    }

    /** Sends a player who just logged in what happened to their contracts while they were away. */
    public static void loggedIn(ServerPlayer player) {
        for (ContractBoard.Notice notice : ContractBoard.get(player.server).takeNotices(player.getUUID())) {
            player.sendSystemMessage(message(player.server, notice));
        }
    }

    private static Component message(MinecraftServer server, ContractBoard.Notice notice) {
        Component item = BuiltInRegistries.ITEM.get(notice.item()).getDescription();
        if (notice.lost() == 0) {
            return Component.translatable("message.variable_economy_contracts.contract.ended_kept", item, notice.delivered(), notice.units(),
                    CoreApi.formatMoney(notice.paid()));
        }
        return Component.translatable("message.variable_economy_contracts.contract.ended", item, notice.delivered(), notice.units(),
                CoreApi.formatMoney(notice.paid()), CoreApi.formatMoney(notice.lost()));
    }

    /**
     * What delivering {@code delivered} units of the contract pays right now: capped below the lowest price the market
     * could charge for them ({@link #cap}), and never more than the contract offered.
     */
    public static long payout(MinecraftServer server, Contract contract, int delivered) {
        if (delivered <= 0) {
            return 0;
        }
        OptionalLong lowest = BuiltInRegistries.ITEM.getOptional(contract.item())
                .map(item -> cap(server, item, delivered))
                .orElse(OptionalLong.empty());
        return Math.min(contract.reward(), ContractPricing.payout(contract.unitReward(), lowest, delivered));
    }

    // ---- Player actions --------------------------------------------------------------------------------------

    public static Outcome claim(ServerPlayer player, int id) {
        return claim(player, ContractBoard.get(player.server), id);
    }

    /** Claims an open contract: takes the deposit and locks it to the player. */
    public static Outcome claim(ServerPlayer player, ContractBoard board, int id) {
        requireServerThread(player);
        Optional<Contract> found = current(player, board, id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.CLAIM, ContractResult.CONTRACT_EXPIRED, none());
        }
        Contract contract = found.get();
        Item item = BuiltInRegistries.ITEM.get(contract.item());
        // Shown greyed out, so the reply can say why.
        if (CoreApi.itemGate().isLocked(player, item) || missingStage(player, contract).isPresent()) {
            return Outcome.fail(Action.CLAIM, ContractResult.LOCKED, contract.item());
        }
        if (contract.status() != Status.OPEN) {
            return Outcome.fail(Action.CLAIM, ContractResult.CONTRACT_TAKEN, contract.item());
        }
        if (board.claimedBy(player.getUUID()) >= ContractSettings.maxClaims()) {
            return Outcome.fail(Action.CLAIM, ContractResult.CONTRACT_LIMIT, contract.item());
        }
        AccountId account = CoreApi.accounts().active(player);
        // The charge is itself the funds check: it changes nothing unless it succeeds. A free contract takes nothing.
        if (contract.deposit() > 0) {
            PaymentResult charged = CoreApi.money().charge(player.server, account, contract.deposit(), Reason.by(DEPOSIT, player.getUUID()));
            if (!charged.ok()) {
                return Outcome.fail(Action.CLAIM, charged.result() == BankResult.NO_FUNDS ? ContractResult.NO_FUNDS : ContractResult.BANK_UNAVAILABLE,
                        contract.item());
            }
        }
        // Payouts go back to the account the deposit came from.
        board.update(contract.claimed(player.getUUID(), account.value(), board.day() + ContractSettings.durationDays()));
        ContractsEvents.post(new ContractEvent.Claimed(player.server, contract.id(), contract.definition(), player.getUUID(), contract.item(), contract.units(),
                contract.delivered(), contract.deposit()));
        return new Outcome(Action.CLAIM, ContractResult.OK, contract.item(), 0, contract.deposit());
    }

    public static Outcome deliver(ServerPlayer player, int id, int qty) {
        return deliver(player, ContractBoard.get(player.server), id, qty);
    }

    /**
     * Delivers {@code qty} units of a held contract from the main inventory. The last unit pays the reward and
     * returns the deposit.
     */
    public static Outcome deliver(ServerPlayer player, ContractBoard board, int id, int qty) {
        requireServerThread(player);
        Optional<Contract> found = current(player, board, id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.DELIVER, ContractResult.CONTRACT_EXPIRED, none());
        }
        Contract contract = found.get();
        ContractResult holding = holding(player, contract);
        if (holding != ContractResult.OK) {
            return Outcome.fail(Action.DELIVER, holding, contract.item());
        }
        // When batches are off, the one delivery a contract allows may exceed the per-request limit, or it could never finish.
        ContractResult quantityCheck = checkQuantity(qty);
        if (quantityCheck != ContractResult.OK && !(quantityCheck == ContractResult.TOO_LARGE && !ContractSettings.partialAllowed()
                && qty == contract.remaining())) {
            return Outcome.fail(Action.DELIVER, quantityCheck, contract.item());
        }
        if (qty > contract.remaining()) {
            return Outcome.fail(Action.DELIVER, ContractResult.CONTRACT_FULL, contract.item());
        }
        if (qty < contract.remaining() && !ContractSettings.partialAllowed()) {
            return Outcome.fail(Action.DELIVER, ContractResult.WHOLE_DELIVERY, contract.item());
        }
        Item item = BuiltInRegistries.ITEM.get(contract.item());
        Predicate<ItemStack> matches = stack -> CoreApi.isPlain(stack, item);
        if (PlayerItems.count(player, matches) < qty) {
            return Outcome.fail(Action.DELIVER, ContractResult.NOT_ENOUGH_ITEMS, contract.item());
        }
        boolean completes = qty == contract.remaining();
        long paid = completes ? payout(player.server, contract, contract.units()) : 0;
        Optional<AccountId> account = Optional.empty();
        if (completes) {
            account = payee(player.server, player.getUUID(), contract.account(), paid + contract.deposit());
            if (account.isEmpty()) {
                return Outcome.fail(Action.DELIVER, ContractResult.BANK_UNAVAILABLE, contract.item());
            }
        }

        // Nothing has changed yet. Everything below always succeeds: the account takes the payout (checked above).
        PlayerItems.remove(player, matches, qty);
        board.update(contract.withDelivered(contract.delivered() + qty));
        if (completes) {
            pay(player.server, account.get(), paid + contract.deposit(), Reason.by(PAYOUT, player.getUUID()));
            board.count(paid, 0);
        }
        int delivered = contract.delivered() + qty;
        ContractsEvents.post(new ContractEvent.Delivered(player.server, contract.id(), contract.definition(), player.getUUID(), contract.item(), contract.units(),
                delivered, qty));
        if (completes) {
            ContractsEvents.post(new ContractEvent.Completed(player.server, contract.id(), contract.definition(), player.getUUID(), contract.item(),
                    contract.units(), delivered, paid, contract.deposit()));
        }
        return new Outcome(Action.DELIVER, ContractResult.OK, contract.item(), qty, completes ? paid + contract.deposit() : 0);
    }

    public static Outcome abandon(ServerPlayer player, int id) {
        return abandon(player, ContractBoard.get(player.server), id);
    }

    /**
     * Gives up a held contract: pays for the units delivered so far and returns {@code deposit.refund_on_abandon} of
     * the deposit; the rest of it is lost.
     */
    public static Outcome abandon(ServerPlayer player, ContractBoard board, int id) {
        requireServerThread(player);
        Optional<Contract> found = current(player, board, id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.ABANDON, ContractResult.CONTRACT_EXPIRED, none());
        }
        Contract contract = found.get();
        ContractResult holding = holding(player, contract);
        if (holding != ContractResult.OK) {
            return Outcome.fail(Action.ABANDON, holding, contract.item());
        }
        long paid = payout(player.server, contract, contract.delivered());
        long returned = ContractPricing.refund(contract.deposit(), ContractSettings.refundOnAbandon());
        long lost = contract.deposit() - returned;
        Optional<AccountId> account = payee(player.server, player.getUUID(), contract.account(), paid + returned);
        if (paid + returned > 0 && account.isEmpty()) {
            return Outcome.fail(Action.ABANDON, ContractResult.BANK_UNAVAILABLE, contract.item());
        }

        board.update(contract.abandoned());
        if (paid + returned > 0) {
            pay(player.server, account.get(), paid + returned, Reason.by(PAYOUT, player.getUUID()));
        }
        board.count(paid, lost);
        ContractsEvents.post(new ContractEvent.Abandoned(player.server, contract.id(), contract.definition(), player.getUUID(), contract.item(), contract.units(),
                contract.delivered(), paid, returned, lost));
        return new Outcome(Action.ABANDON, ContractResult.OK, contract.item(), contract.delivered(), paid + returned);
    }

    /**
     * The contract, if it's still current: a held one until its own time runs out, any other until its board's day
     * comes. Either counts as gone from that day, even before the tick ends it, so nothing is claimed or delivered
     * after expiry.
     */
    private static Optional<Contract> current(ServerPlayer player, ContractBoard board, int id) {
        board.observe(ContractBoard.worldDay(player.server));
        return board.find(id).filter(c -> c.status() == Status.CLAIMED ? board.day() < c.deadline() : !board.due());
    }

    /** OK if the player holds the contract and it's still open to deliveries. */
    private static ContractResult holding(ServerPlayer player, Contract contract) {
        if (contract.holder().isEmpty() || !contract.holder().get().equals(player.getUUID())) {
            return ContractResult.NOT_YOUR_CONTRACT;
        }
        return contract.status() == Status.CLAIMED ? ContractResult.OK : ContractResult.CONTRACT_TAKEN;
    }

    // ---- Paying ----------------------------------------------------------------------------------------------

    /**
     * The account a contract's money goes back to: the account its deposit came from while that still exists,
     * otherwise the holder's own. Empty if neither can take {@code amount} now.
     */
    static Optional<AccountId> payee(MinecraftServer server, UUID holder, Optional<UUID> from, long amount) {
        Accounts accounts = CoreApi.accounts();
        List<AccountId> candidates = new ArrayList<>();
        from.ifPresent(id -> candidates.add(new AccountId(id)));
        candidates.add(accounts.personal(holder));
        for (AccountId candidate : candidates) {
            if (accounts.get(server, candidate).isPresent()) {
                java.util.OptionalLong balance = CoreApi.money().balance(server, candidate);
                return balance.isPresent() && balance.getAsLong() <= Long.MAX_VALUE - amount ? Optional.of(candidate) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Pays an account {@link #payee} chose and checked. */
    private static void pay(MinecraftServer server, AccountId account, long amount, Reason reason) {
        if (amount <= 0) {
            return;
        }
        PaymentResult result = CoreApi.money().pay(server, account, amount, reason);
        if (!result.ok()) {
            ContractsModule.LOGGER.error("A contract payment of {} to {} failed after its checks passed: {}", amount, account, result.result());
        }
    }

    /**
     * Pays money back to whoever a contract's money goes to, when nobody is there to refuse: at expiry, or when
     * contracts are turned off. Owed by core if the bank can't be reached.
     */
    private static void payBack(MinecraftServer server, UUID holder, Optional<UUID> from, long amount, Reason reason) {
        if (amount <= 0) {
            return;
        }
        Optional<AccountId> account = payee(server, holder, from, amount);
        pay(server, account.orElseGet(() -> CoreApi.accounts().personal(holder)), amount, reason);
    }

    private static ContractResult checkQuantity(int qty) {
        if (qty <= 0) {
            return ContractResult.INVALID_QUANTITY;
        }
        return qty > ContractSettings.maxQuantityPerRequest() ? ContractResult.TOO_LARGE : ContractResult.OK;
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("Contract actions must run on the server thread");
        }
    }

    /** A player's name: online, or as the server last saw them. */
    public static Optional<String> name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Optional.of(online.getGameProfile().getName());
        }
        return server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(id).map(GameProfile::getName);
    }

    private static ResourceLocation none() {
        return BuiltInRegistries.ITEM.getDefaultKey();
    }

    // ---- Net worth -------------------------------------------------------------------------------------------

    /** The contracts' entry on the leaderboard: the deposits of held contracts. */
    public static final ResourceLocation NET_WORTH = ResourceLocation.fromNamespaceAndPath(ContractsModule.MOD_ID, "contract_deposits");

    /** Registers the contracts' net-worth contributor. Once, while the mod is constructed. */
    public static void registerNetWorth() {
        CoreApi.netWorth().register(NET_WORTH, ContractService::heldDeposits);
    }

    /**
     * The deposits held contracts hold, by the account they go back to: the account they were paid from while that
     * exists, otherwise the holder's own.
     */
    public static Map<AccountId, Long> heldDeposits(MinecraftServer server) {
        return heldDeposits(server, ContractBoard.get(server));
    }

    /** The same for the given board. */
    public static Map<AccountId, Long> heldDeposits(MinecraftServer server, ContractBoard board) {
        Accounts accounts = CoreApi.accounts();
        Map<AccountId, Long> deposits = new LinkedHashMap<>();
        for (Contract contract : held(board)) {
            if (contract.deposit() > 0) {
                AccountId account = contract.account().map(AccountId::new).filter(id -> accounts.get(server, id).isPresent())
                        .orElseGet(() -> accounts.personal(contract.holder().get()));
                deposits.merge(account, contract.deposit(), Long::sum);
            }
        }
        return deposits;
    }

    // ---- Viewing ---------------------------------------------------------------------------------------------

    public static List<View> view(ServerPlayer player) {
        return view(player, ContractBoard.get(player.server));
    }

    /**
     * The stage the contract's definition needs that {@code player} lacks, if any. Read from the definition as loaded
     * now; a contract whose definition is gone needs none.
     */
    public static Optional<String> missingStage(ServerPlayer player, Contract contract) {
        return ContractDefinitions.INSTANCE.get(contract.definition()).flatMap(ContractDefinition::stage)
                .filter(stage -> !CoreApi.itemGate().hasStage(player, stage));
    }

    /**
     * The board as {@code player} sees it: every contract, those they can't claim marked locked (their item locked
     * for them, or their definition's stage missing). A contract they hold is never marked, so it stays deliverable
     * if they lose the item or stage later.
     */
    public static List<View> view(ServerPlayer player, ContractBoard board) {
        List<View> views = new ArrayList<>();
        for (Contract contract : board.contracts()) {
            boolean mine = contract.holder().isPresent() && contract.holder().get().equals(player.getUUID());
            boolean itemLocked = !mine && CoreApi.itemGate().isLocked(player, BuiltInRegistries.ITEM.get(contract.item()));
            Optional<String> missingStage = mine ? Optional.empty() : missingStage(player, contract);
            String holder = contract.holder().flatMap(id -> name(player.server, id)).orElse("");
            boolean holding = mine && contract.status() == Status.CLAIMED;
            views.add(new View(contract, payout(player.server, contract, contract.units()), holder, mine,
                    holding ? board.daysLeft(contract) : -1,
                    holding ? payout(player.server, contract, contract.delivered())
                            + ContractPricing.refund(contract.deposit(), ContractSettings.refundOnAbandon()) : 0,
                    itemLocked, missingStage));
        }
        return views;
    }
}
