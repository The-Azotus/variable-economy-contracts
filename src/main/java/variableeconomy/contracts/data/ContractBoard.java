package variableeconomy.contracts.data;

import variableeconomy.contracts.ContractsModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The contract board, saved with the world: the contracts on offer, who holds which, and the day count that decides
 * when the board is replaced. Only {@code ContractService} changes it. Server thread only.
 * <p>
 * The day count is the overworld's day number as first seen, then advanced by every day boundary the world's time
 * crosses forwards (sleeping included). Setting the time backwards doesn't move it, so {@code /time set day} can't
 * replace a board early, or hold one far past its expiry.
 */
public final class ContractBoard extends SavedData {
    public static final String ID = "variable_economy_contracts";
    /** The definition of a contract saved before contracts had definitions. */
    public static final ResourceLocation UNKNOWN_DEFINITION = ResourceLocation.fromNamespaceAndPath(ContractsModule.MOD_ID, "unknown");
    private static final SavedData.Factory<ContractBoard> FACTORY = new SavedData.Factory<>(ContractBoard::new, ContractBoard::fromTag, null);

    public enum Status {
        /** On offer: anyone may claim it. */
        OPEN,
        /** Held by a player, who may deliver or abandon it. */
        CLAIMED,
        /** Every unit delivered and paid; stays on the board, done, until it's replaced. */
        COMPLETED,
        /** Given up by its holder, who was paid for what they had delivered. */
        ABANDONED
    }

    /**
     * One contract.
     *
     * @param definition the contract definition it came from
     * @param unitReward the reward per unit before the cap, fixed when the board was made
     * @param reward     what completing it paid when it was offered; payouts are worked out again when made
     * @param deposit    taken when claimed, returned on completion
     * @param account    the account the deposit came from, which payouts go back to while it exists; empty while open
     * @param deadline   for a held contract, the day count it expires at; -1 otherwise
     */
    public record Contract(int id, ResourceLocation definition, ResourceLocation item, int units, BigDecimal unitReward, long reward,
                           long deposit, int delivered, Optional<UUID> holder, Optional<UUID> account, long deadline, Status status) {
        public int remaining() {
            return units - delivered;
        }

        public boolean heldBy(UUID player) {
            return status == Status.CLAIMED && holder.isPresent() && holder.get().equals(player);
        }

        public Contract claimed(UUID player, UUID fromAccount, long until) {
            return new Contract(id, definition, item, units, unitReward, reward, deposit, delivered, Optional.of(player),
                    Optional.of(fromAccount), until, Status.CLAIMED);
        }

        public Contract withDelivered(int total) {
            return new Contract(id, definition, item, units, unitReward, reward, deposit, total, holder, account, deadline,
                    total >= units ? Status.COMPLETED : status);
        }

        public Contract abandoned() {
            return new Contract(id, definition, item, units, unitReward, reward, deposit, delivered, holder, account, deadline,
                    Status.ABANDONED);
        }
    }

    /**
     * How a held contract ended while its holder may not have been there to see it, kept until they next log in.
     *
     * @param paid what the units delivered paid
     * @param lost the deposit kept
     */
    public record Notice(UUID player, ResourceLocation item, int delivered, int units, long paid, long lost) {
    }

    private final List<Contract> contracts = new ArrayList<>();
    private final List<Notice> notices = new ArrayList<>();
    private long day = -1;
    private long lastWorldDay = -1;
    /** The day count the current board expires at; -1 when there's no board yet. */
    private long expiresDay = -1;
    private int nextId = 1;
    private long rewardsPaid;
    private long depositsLost;

    public static ContractBoard get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** The overworld's day number: what the debug screen calls the day. */
    public static long worldDay(MinecraftServer server) {
        return server.overworld().getDayTime() / 24000L;
    }

    /** Advances the day count by every day boundary crossed forwards since the last call. */
    public void observe(long worldDay) {
        if (lastWorldDay < 0 || day < 0) {
            day = Math.max(0, worldDay);
        } else if (worldDay > lastWorldDay) {
            day += worldDay - lastWorldDay;
        }
        if (worldDay != lastWorldDay) {
            lastWorldDay = worldDay;
            setDirty();
        }
    }

    public long day() {
        return Math.max(0, day);
    }

    public long expiresDay() {
        return expiresDay;
    }

    /** Whether the board should be replaced: there is none yet, or its day has come. */
    public boolean due() {
        return expiresDay < 0 || day() >= expiresDay;
    }

    /** Days until the board is replaced; 0 when it's due. */
    public long daysLeft() {
        return Math.max(0, expiresDay - day());
    }

    public List<Contract> contracts() {
        return Collections.unmodifiableList(contracts);
    }

    public Optional<Contract> find(int id) {
        return contracts.stream().filter(c -> c.id() == id).findFirst();
    }

    /** Replaces the contract with the same ID. */
    public void update(Contract contract) {
        for (int i = 0; i < contracts.size(); i++) {
            if (contracts.get(i).id() == contract.id()) {
                contracts.set(i, contract);
                setDirty();
                return;
            }
        }
        throw new IllegalArgumentException("No contract " + contract.id());
    }

    /**
     * A new board: these contracts, expiring at the next multiple of {@code refreshDays} in the day count. Contracts
     * players hold stay until they're done or their own time runs out; open and finished ones go.
     */
    public void replace(List<Contract> fresh, int refreshDays) {
        contracts.removeIf(c -> c.status() != Status.CLAIMED);
        contracts.addAll(fresh);
        expiresDay = (day() / refreshDays + 1) * refreshDays;
        setDirty();
    }

    /** Takes a contract off the board, as when a held one's time has run out. */
    public void remove(int id) {
        if (contracts.removeIf(c -> c.id() == id)) {
            setDirty();
        }
    }

    /** Held contracts whose time has run out by today's day count. */
    public List<Contract> overdue() {
        return contracts.stream().filter(c -> c.status() == Status.CLAIMED && c.deadline() >= 0 && day() >= c.deadline()).toList();
    }

    /** Days a held contract has left; 0 when it's due. */
    public long daysLeft(Contract contract) {
        return Math.max(0, contract.deadline() - day());
    }

    /** No board at all, e.g. while contracts are turned off; one is put up as soon as they're back on. */
    public void clear() {
        contracts.clear();
        expiresDay = -1;
        setDirty();
    }

    /** Takes the next contract ID. IDs never repeat, so a request for a contract on an old board can't hit a new one. */
    public int takeId() {
        setDirty();
        return nextId++;
    }

    public int claimedBy(UUID player) {
        return (int) contracts.stream().filter(c -> c.heldBy(player)).count();
    }

    public void addNotice(Notice notice) {
        notices.add(notice);
        setDirty();
    }

    /** Removes and returns the player's notices. */
    public List<Notice> takeNotices(UUID player) {
        List<Notice> theirs = notices.stream().filter(n -> n.player().equals(player)).toList();
        if (!theirs.isEmpty()) {
            notices.removeAll(theirs);
            setDirty();
        }
        return theirs;
    }


    /** Adds to the money counts: rewards paid out (money created) and deposits kept (money destroyed). */
    public void count(long paid, long lost) {
        rewardsPaid += paid;
        depositsLost += lost;
        setDirty();
    }

    public long rewardsPaid() {
        return rewardsPaid;
    }

    public long depositsLost() {
        return depositsLost;
    }

    public static ContractBoard fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        ContractBoard board = new ContractBoard();
        board.day = tag.contains("day") ? tag.getLong("day") : -1;
        board.lastWorldDay = tag.contains("world_day") ? tag.getLong("world_day") : -1;
        board.expiresDay = tag.contains("expires") ? tag.getLong("expires") : -1;
        board.nextId = Math.max(1, tag.getInt("next_id"));
        board.rewardsPaid = tag.getLong("rewards_paid");
        board.depositsLost = tag.getLong("deposits_lost");
        for (Tag element : tag.getList("contracts", Tag.TAG_COMPOUND)) {
            readContract((CompoundTag) element, board.expiresDay).ifPresent(board.contracts::add);
        }
        for (Tag element : tag.getList("notices", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) element;
            ResourceLocation item = ResourceLocation.tryParse(n.getString("item"));
            if (n.hasUUID("player") && item != null) {
                board.notices.add(new Notice(n.getUUID("player"), item, n.getInt("delivered"), n.getInt("units"),
                        n.getLong("paid"), n.getLong("lost")));
            }
        }
        return board;
    }

    /**
     * A saved contract, or empty if it can't be used. A reward per unit that can't be read is rebuilt from the reward
     * the contract offered, which never pays more. A contract whose item or status can't be read is dropped with an
     * error naming its holder and deposit: its deposit isn't refunded automatically, since a completed contract has
     * already had its deposit back and refunding it again would create money. A held contract saved before contracts
     * had deadlines (version 1) expires with the board it was on, {@code boardExpires}, as it would have then.
     */
    private static Optional<Contract> readContract(CompoundTag c, long boardExpires) {
        ResourceLocation item = ResourceLocation.tryParse(c.getString("item"));
        Status status = null;
        try {
            status = Status.valueOf(c.getString("status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // Reported below.
        }
        int units = c.getInt("units");
        if (item == null || status == null || units <= 0) {
            if (c.hasUUID("holder")) {
                ContractsModule.LOGGER.error("Saved contract {} can't be read (item \"{}\", status \"{}\", {} units), so it's dropped. "
                        + "It was held by {} with a deposit of {}: if it was still claimed, refund the deposit by hand",
                        c.getInt("id"), c.getString("item"), c.getString("status"), units, c.getUUID("holder"), c.getLong("deposit"));
            } else {
                ContractsModule.LOGGER.warn("Saved contract {} can't be read (item \"{}\", status \"{}\", {} units); nobody held it, so it's dropped",
                        c.getInt("id"), c.getString("item"), c.getString("status"), units);
            }
            return Optional.empty();
        }
        BigDecimal unitReward;
        try {
            unitReward = new BigDecimal(c.getString("unit_reward"));
        } catch (NumberFormatException e) {
            // Rounded up, so that payout(), which rounds down, pays the whole reward for every unit; it's capped at
            // the reward anyway, so this can't pay more.
            unitReward = BigDecimal.valueOf(c.getLong("reward")).divide(BigDecimal.valueOf(units), new MathContext(34, RoundingMode.CEILING));
            ContractsModule.LOGGER.warn("Saved contract {}: its reward per unit \"{}\" can't be read, so it pays from the reward it offered "
                    + "({} for {} units)", c.getInt("id"), c.getString("unit_reward"), c.getLong("reward"), units);
        }
        // Version 1 named the paying account "team".
        Optional<UUID> account = c.hasUUID("account") ? Optional.of(c.getUUID("account"))
                : c.hasUUID("team") ? Optional.of(c.getUUID("team")) : Optional.empty();
        long deadline = c.contains("deadline") ? c.getLong("deadline") : status == Status.CLAIMED ? boardExpires : -1;
        ResourceLocation definition = Optional.ofNullable(ResourceLocation.tryParse(c.getString("definition"))).orElse(UNKNOWN_DEFINITION);
        return Optional.of(new Contract(c.getInt("id"), definition, item, units, unitReward, c.getLong("reward"),
                c.getLong("deposit"), c.getInt("delivered"), c.hasUUID("holder") ? Optional.of(c.getUUID("holder")) : Optional.empty(),
                account, deadline, status));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 2);
        tag.putLong("day", day);
        tag.putLong("world_day", lastWorldDay);
        tag.putLong("expires", expiresDay);
        tag.putInt("next_id", nextId);
        tag.putLong("rewards_paid", rewardsPaid);
        tag.putLong("deposits_lost", depositsLost);
        ListTag list = new ListTag();
        for (Contract contract : contracts) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", contract.id());
            c.putString("definition", contract.definition().toString());
            c.putString("item", contract.item().toString());
            c.putInt("units", contract.units());
            c.putString("unit_reward", contract.unitReward().toPlainString());
            c.putLong("reward", contract.reward());
            c.putLong("deposit", contract.deposit());
            c.putInt("delivered", contract.delivered());
            contract.holder().ifPresent(id -> c.putUUID("holder", id));
            contract.account().ifPresent(id -> c.putUUID("account", id));
            c.putLong("deadline", contract.deadline());
            c.putString("status", contract.status().name().toLowerCase(Locale.ROOT));
            list.add(c);
        }
        tag.put("contracts", list);
        ListTag noticeList = new ListTag();
        for (Notice notice : notices) {
            CompoundTag n = new CompoundTag();
            n.putUUID("player", notice.player());
            n.putString("item", notice.item().toString());
            n.putInt("delivered", notice.delivered());
            n.putInt("units", notice.units());
            n.putLong("paid", notice.paid());
            n.putLong("lost", notice.lost());
            noticeList.add(n);
        }
        tag.put("notices", noticeList);
        return tag;
    }
}
