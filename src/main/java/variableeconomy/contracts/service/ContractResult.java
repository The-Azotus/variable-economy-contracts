package variableeconomy.contracts.service;

/** How a contract request went. Only {@link #OK} changed anything. */
public enum ContractResult {
    OK,
    /** The player hasn't unlocked the contract's item, or lacks the stage its definition needs. */
    LOCKED,
    NOT_ENOUGH_ITEMS,
    NO_FUNDS,
    /** More than one request may move, or a deposit larger than one payment. */
    TOO_LARGE,
    /** Quantity was zero or negative. */
    INVALID_QUANTITY,
    /** The bank can't be reached. The mod's own bank always can, so this isn't returned today. */
    BANK_UNAVAILABLE,
    /** Another player holds it, or it's already done. */
    CONTRACT_TAKEN,
    /** It's no longer on the board: the board was replaced, or a held contract's time ran out. */
    CONTRACT_EXPIRED,
    /** The player already holds as many as they may. */
    CONTRACT_LIMIT,
    /** The player doesn't hold it. */
    NOT_YOUR_CONTRACT,
    /** More than the units still wanted. */
    CONTRACT_FULL,
    /** Fewer than the units still wanted, when deliveries can't come in batches (delivery.partial_allowed false). */
    WHOLE_DELIVERY
}
