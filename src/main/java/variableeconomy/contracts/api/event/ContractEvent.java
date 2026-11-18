package variableeconomy.contracts.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Something happened to a contract held by a player. Listen for one of the nested events, or for this class to hear
 * them all. Each is posted once the action is complete, money included.
 * <p>
 * Money paid to the holder goes to the account the deposit came from, while it exists (otherwise their personal
 * account). If the bank
 * can't be reached when a contract expires, the money is owed and paid later; the event is posted either way.
 * <p>
 * Posted by Variable Economy; add-ons only listen for them. The class isn't abstract only so that a listener can be
 * registered for it (NeoForge refuses abstract event classes); it is only ever posted as one of the nested events.
 */
public class ContractEvent extends Event {
    private final MinecraftServer server;
    private final int contractId;
    private final ResourceLocation definition;
    private final UUID holder;
    private final ResourceLocation item;
    private final int units;
    private final int delivered;

    /**
     * Posted by Variable Economy.
     *
     * @param server     the server
     * @param contractId the contract's ID on the board
     * @param definition the contract definition it came from
     * @param holder     the player who holds the contract
     * @param item       the item
     * @param units      how many units the contract asks for in all
     * @param delivered  how many units have been delivered in all, counting this action
     */
    protected ContractEvent(MinecraftServer server, int contractId, ResourceLocation definition, UUID holder, ResourceLocation item,
                            int units, int delivered) {
        this.server = server;
        this.contractId = contractId;
        this.definition = definition;
        this.holder = holder;
        this.item = item;
        this.units = units;
        this.delivered = delivered;
    }

    /**
     * The server the contract is on.
     *
     * @return the server the contract is on
     */
    public MinecraftServer getServer() {
        return server;
    }

    /**
     * The contract's ID on the board, as {@code /contracts list} shows it.
     *
     * @return the contract's ID on the board, as {@code /contracts list} shows it
     */
    public int getContractId() {
        return contractId;
    }

    /**
     * The ID of the contract definition the contract came from, as {@code /contracts admin definitions} lists it.
     *
     * @return the definition's ID
     */
    public ResourceLocation getDefinition() {
        return definition;
    }

    /**
     * The player who holds (or held) the contract. They may be offline when it expires.
     *
     * @return the player who holds (or held) the contract. They may be offline when it expires
     */
    public UUID getHolder() {
        return holder;
    }

    /**
     * The ID of the item the contract asks for. An ID rather than an {@code Item}, since a contract can outlive the
     * mod that adds its item.
     *
     * @return the item's ID
     */
    public ResourceLocation getItem() {
        return item;
    }

    /**
     * How many units the contract asks for in all.
     *
     * @return how many units the contract asks for in all
     */
    public int getUnits() {
        return units;
    }

    /**
     * How many units have been delivered in all, counting this action.
     *
     * @return how many units have been delivered in all, counting this action
     */
    public int getDelivered() {
        return delivered;
    }

    /** A player claimed an open contract and paid its deposit. */
    public static final class Claimed extends ContractEvent {
        private final long deposit;

        /**
         * Posted by Variable Economy.
         *
         * @param server     the server
         * @param contractId the contract's ID on the board
         * @param definition the contract definition it came from
         * @param holder     the player who holds the contract
         * @param item       the item
         * @param units      how many units the contract asks for in all
         * @param delivered  how many units have been delivered in all, counting this action
         * @param deposit    the deposit, in bits
         */
        public Claimed(MinecraftServer server, int contractId, ResourceLocation definition, UUID holder, ResourceLocation item, int units, int delivered,
                       long deposit) {
            super(server, contractId, definition, holder, item, units, delivered);
            this.deposit = deposit;
        }

        /**
         * The deposit taken, in bits.
         *
         * @return the deposit taken, in bits
         */
        public long getDeposit() {
            return deposit;
        }
    }

    /**
     * The holder delivered units. Posted for every delivery, the last one included; when the last one completes the
     * contract, {@link Completed} follows it.
     */
    public static final class Delivered extends ContractEvent {
        private final int quantity;

        /**
         * Posted by Variable Economy.
         *
         * @param server     the server
         * @param contractId the contract's ID on the board
         * @param definition the contract definition it came from
         * @param holder     the player who holds the contract
         * @param item       the item
         * @param units      how many units the contract asks for in all
         * @param delivered  how many units have been delivered in all, counting this action
         * @param quantity   how many units this action moved
         */
        public Delivered(MinecraftServer server, int contractId, ResourceLocation definition, UUID holder, ResourceLocation item, int units, int delivered,
                         int quantity) {
            super(server, contractId, definition, holder, item, units, delivered);
            this.quantity = quantity;
        }

        /**
         * How many units this delivery brought.
         *
         * @return how many units this delivery brought
         */
        public int getQuantity() {
            return quantity;
        }
    }

    /** The last unit was delivered: the reward was paid and the deposit returned. Posted after {@link Delivered}. */
    public static final class Completed extends ContractEvent {
        private final long reward;
        private final long deposit;

        /**
         * Posted by Variable Economy.
         *
         * @param server     the server
         * @param contractId the contract's ID on the board
         * @param definition the contract definition it came from
         * @param holder     the player who holds the contract
         * @param item       the item
         * @param units      how many units the contract asks for in all
         * @param delivered  how many units have been delivered in all, counting this action
         * @param reward     the reward paid, in bits, not counting the deposit
         * @param deposit    the deposit, in bits
         */
        public Completed(MinecraftServer server, int contractId, ResourceLocation definition, UUID holder, ResourceLocation item, int units, int delivered,
                         long reward, long deposit) {
            super(server, contractId, definition, holder, item, units, delivered);
            this.reward = reward;
            this.deposit = deposit;
        }

        /**
         * The reward paid, in bits, not counting the deposit.
         *
         * @return the reward paid, in bits, not counting the deposit
         */
        public long getReward() {
            return reward;
        }

        /**
         * The deposit returned, in bits.
         *
         * @return the deposit returned, in bits
         */
        public long getDeposit() {
            return deposit;
        }
    }

    /**
     * The holder gave the contract up: the units delivered so far were paid for, and part of the deposit
     * ({@code deposit.refund_on_abandon} in the server config) was returned.
     */
    public static final class Abandoned extends ContractEvent {
        private final long paid;
        private final long depositReturned;
        private final long depositLost;

        /**
         * Posted by Variable Economy.
         *
         * @param server          the server
         * @param contractId      the contract's ID on the board
         * @param definition      the contract definition it came from
         * @param holder          the player who holds the contract
         * @param item            the item
         * @param units           how many units the contract asks for in all
         * @param delivered       how many units have been delivered in all, counting this action
         * @param paid            what the units delivered paid, in bits
         * @param depositReturned the part of the deposit returned, in bits
         * @param depositLost     the part of the deposit kept, in bits
         */
        public Abandoned(MinecraftServer server, int contractId, ResourceLocation definition, UUID holder, ResourceLocation item, int units, int delivered,
                         long paid, long depositReturned, long depositLost) {
            super(server, contractId, definition, holder, item, units, delivered);
            this.paid = paid;
            this.depositReturned = depositReturned;
            this.depositLost = depositLost;
        }

        /**
         * What the units delivered so far paid, in bits; 0 if none were delivered. Not counting the deposit returned.
         *
         * @return what the units delivered so far paid, in bits
         */
        public long getPaid() {
            return paid;
        }

        /**
         * The part of the deposit returned, in bits.
         *
         * @return the part of the deposit returned, in bits
         */
        public long getDepositReturned() {
            return depositReturned;
        }

        /**
         * The part of the deposit kept, in bits.
         *
         * @return the part of the deposit kept, in bits
         */
        public long getDepositLost() {
            return depositLost;
        }
    }

    /**
     * A held contract ended unfinished: its time ran out ({@code contract.duration_days} after it was claimed), or
     * contracts were turned off. The units delivered so far were paid for. When its time ran out, part of the deposit
     * ({@code deposit.refund_on_expiry}) is returned; when contracts were turned off, all of it, since that wasn't the
     * holder's doing.
     * <p>
     * When several end at once, their events are posted together once the board has changed.
     */
    public static final class Expired extends ContractEvent {
        private final long paid;
        private final long depositReturned;
        private final long depositLost;

        /**
         * Posted by Variable Economy.
         *
         * @param server          the server
         * @param contractId      the contract's ID on the board
         * @param definition      the contract definition it came from
         * @param holder          the player who holds the contract
         * @param item            the item
         * @param units           how many units the contract asks for in all
         * @param delivered       how many units have been delivered in all
         * @param paid            what the units delivered paid, in bits
         * @param depositReturned the part of the deposit returned, in bits
         * @param depositLost     the part of the deposit kept, in bits
         */
        public Expired(MinecraftServer server, int contractId, ResourceLocation definition, UUID holder, ResourceLocation item, int units, int delivered,
                       long paid, long depositReturned, long depositLost) {
            super(server, contractId, definition, holder, item, units, delivered);
            this.paid = paid;
            this.depositReturned = depositReturned;
            this.depositLost = depositLost;
        }

        /**
         * What the units delivered paid, in bits; 0 if none were delivered. Not counting the deposit returned.
         *
         * @return what the units delivered paid, in bits
         */
        public long getPaid() {
            return paid;
        }

        /**
         * The part of the deposit returned, in bits.
         *
         * @return the part of the deposit returned, in bits
         */
        public long getDepositReturned() {
            return depositReturned;
        }

        /**
         * The part of the deposit kept, in bits; 0 when all of it was returned.
         *
         * @return the part of the deposit kept, in bits
         */
        public long getDepositLost() {
            return depositLost;
        }
    }
}
