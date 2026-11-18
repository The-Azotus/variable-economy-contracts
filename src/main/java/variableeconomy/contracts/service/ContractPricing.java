package variableeconomy.contracts.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongUnaryOperator;

import variableeconomy.contracts.ContractSettings;

/**
 * Contract money, in bits. A contract offers {@code base × (1 + contract_premium)} per unit. What delivering {@code d}
 * units pays is that times {@code d}, rounded down once, but always at least one bit less than buying those {@code d}
 * units from the market could ever cost ({@code lowestBuyCost}, from the market's API). Buying from the market to fill
 * a contract therefore never profits, however cheaply or how long ago the items were bought. An item is offered only
 * if a contract would still pay more than selling the same units at the item's normal price, so the cap leaves most
 * finite-stock items off the board: with the built-in classes their price can fall to a third of base.
 * <p>
 * Pure: everything it needs is passed in, as the market reports it.
 */
public final class ContractPricing {
    private ContractPricing() {
    }

    /** One contract's terms, as offered on a board. */
    public record Offer(int units, BigDecimal unitReward, long reward, long deposit) {
    }

    /** The reward per unit before the cap: {@code basePrice × (1 + premium)}. */
    public static BigDecimal unitReward(long basePrice, double premium) {
        return BigDecimal.valueOf(basePrice).multiply(BigDecimal.ONE.add(BigDecimal.valueOf(premium)));
    }

    /**
     * What delivering {@code delivered} units pays: {@code delivered × unitReward} rounded down, but below
     * {@code lowestBuyCost}, the least buying those units could cost (rounded up), by at least a bit. No cap when the
     * market sets none. Capped at {@link Long#MAX_VALUE}, which {@link #offer} refuses.
     */
    public static long payout(BigDecimal unitReward, OptionalLong lowestBuyCost, int delivered) {
        if (delivered <= 0) {
            return 0;
        }
        BigDecimal reward = BigDecimal.valueOf(delivered).multiply(unitReward).setScale(0, RoundingMode.FLOOR);
        if (lowestBuyCost.isPresent()) {
            reward = reward.min(BigDecimal.valueOf(lowestBuyCost.getAsLong()).subtract(BigDecimal.ONE));
        }
        if (reward.signum() <= 0) {
            return 0;
        }
        return reward.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : reward.longValueExact();
    }

    /**
     * A contract for {@code units} of an item, or empty if one isn't worth offering: the market doesn't buy the item
     * (a contract would reopen what that closed), it would pay no more than selling those units at the item's normal
     * price ({@code normalSellTotal}, empty when too large to count), or the reward plus deposit is too large to count.
     *
     * @param lowestBuyCost the least buying {@code units} could ever cost, or empty for no cap
     */
    public static Optional<Offer> offer(boolean canSell, int units, BigDecimal unitReward, OptionalLong lowestBuyCost,
                                        OptionalLong normalSellTotal, LongUnaryOperator depositOf) {
        if (!canSell || units <= 0) {
            return Optional.empty();
        }
        long reward = payout(unitReward, lowestBuyCost, units);
        if (reward <= 0 || normalSellTotal.isPresent() && reward <= normalSellTotal.getAsLong()) {
            return Optional.empty();
        }
        long deposit = depositOf.applyAsLong(reward);
        if (reward == Long.MAX_VALUE || deposit < 0 || deposit > Long.MAX_VALUE - reward) {
            return Optional.empty();
        }
        return Optional.of(new Offer(units, unitReward, reward, deposit));
    }

    /**
     * The deposit for a contract offering {@code reward}: {@code amount} percent of it rounded up ({@code PERCENT}),
     * or {@code amount} bits with any fraction dropped ({@code FLAT}). {@link Long#MAX_VALUE} if too large to count,
     * which {@link #offer} refuses.
     */
    public static long deposit(long reward, ContractSettings.DepositMode mode, double amount) {
        BigDecimal deposit = mode == ContractSettings.DepositMode.FLAT
                ? BigDecimal.valueOf(amount).setScale(0, RoundingMode.FLOOR)
                : BigDecimal.valueOf(reward).multiply(BigDecimal.valueOf(amount)).divide(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.CEILING);
        return deposit.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : Math.max(0, deposit.longValueExact());
    }

    /** The deposit as the server config sets it now. */
    public static long deposit(long reward) {
        return deposit(reward, ContractSettings.depositMode(), ContractSettings.depositAmount());
    }

    /** The part of {@code deposit} returned: {@code percent} of it, rounded down. */
    public static long refund(long deposit, double percent) {
        if (deposit <= 0 || percent <= 0) {
            return 0;
        }
        return Math.min(deposit, BigDecimal.valueOf(deposit).multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.FLOOR).longValueExact());
    }

    /**
     * Why a contract for {@code units} at {@code unitReward} each can't be offered, or empty if it can: it couldn't pay
     * more than selling those units to the market at base price ({@code normalSellTotal}), once capped below what buying
     * them could cost, or it's too large to count. For admins.
     */
    public static Optional<String> whyNotOffered(int units, BigDecimal unitReward, OptionalLong lowestBuyCost,
                                                 OptionalLong normalSellTotal, LongUnaryOperator depositOf) {
        if (offer(true, units, unitReward, lowestBuyCost, normalSellTotal, depositOf).isPresent()) {
            return Optional.empty();
        }
        String cap = lowestBuyCost.isPresent()
                ? "buying %d could cost as little as %d, ".formatted(units, lowestBuyCost.getAsLong())
                : "";
        String selling = normalSellTotal.isPresent() ? String.valueOf(normalSellTotal.getAsLong()) : "nothing";
        return Optional.of(cap + "so a contract for " + units + " couldn't pay more than selling them (" + selling
                + ") or is too large to count");
    }
}
