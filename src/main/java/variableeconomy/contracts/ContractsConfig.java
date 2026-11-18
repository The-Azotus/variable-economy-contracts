package variableeconomy.contracts;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The contract board's server config, {@code config/variable_economy_contracts-server.toml}, loaded when a server
 * starts. {@link #install} hands its values to {@link ContractSettings}, which the rest of the add-on reads.
 */
public final class ContractsConfig {
    /** The config's file name. */
    public static final String FILE = "variable_economy_contracts-server.toml";
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<AccessMode> ACCESS_MODE;
    public static final ModConfigSpec.IntValue MAX_QUANTITY_PER_REQUEST;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue CLAMP_TO_MARKET;
    public static final ModConfigSpec.IntValue BOARD_SIZE;
    public static final ModConfigSpec.IntValue REFRESH_DAYS;
    public static final ModConfigSpec.IntValue DURATION_DAYS;
    public static final ModConfigSpec.IntValue MAX_CLAIMS;
    public static final ModConfigSpec.EnumValue<ContractSettings.DepositMode> DEPOSIT_MODE;
    public static final ModConfigSpec.DoubleValue DEPOSIT_AMOUNT;
    public static final ModConfigSpec.DoubleValue REFUND_ON_ABANDON;
    public static final ModConfigSpec.DoubleValue REFUND_ON_EXPIRY;
    public static final ModConfigSpec.BooleanValue PARTIAL_ALLOWED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ACCESS_MODE = builder
                .comment("How players reach the contract board.",
                        "COMMAND_AND_BLOCK: the /contracts commands or the Contract Board block.",
                        "BLOCK_ONLY: only the Contract Board block. Players without operator permission (level 2) can't open",
                        "the board or use contracts by command; operators and the console always can.")
                .defineEnum("access_mode", AccessMode.COMMAND_AND_BLOCK);
        MAX_QUANTITY_PER_REQUEST = builder
                .comment("Largest quantity a single delivery may hand in. Larger deliveries are rejected with TOO_LARGE.")
                .defineInRange("max_quantity_per_request", 640, 1, 1_000_000);

        builder.comment("The Contract Board: a board of delivery orders, shared by every player and replaced every few",
                "in-game days. A player claims a contract, paying a deposit, and delivers the items; delivered items",
                "leave the game. Finishing pays the reward and returns the deposit; abandoning or running out of time pays",
                "for the units delivered and returns part of the deposit.").push("contracts");
        ENABLED = builder
                .comment("Offer contracts. When false the board offers none, and turning it off settles every claimed contract:",
                        "delivered units are paid and deposits returned in full.")
                .define("enabled", true);
        CLAMP_TO_MARKET = builder
                .comment("With the market add-on installed, cap every contract's payout below what buying the delivered units from",
                        "the market could ever cost, so buying from the market to deliver never profits, and never offer items the",
                        "market doesn't buy (can_sell false). Turning this off lets definitions pay what they say: the server log",
                        "warns, at every start and reload, about each definition players could then profit from.")
                .define("clamp_to_market", true);
        builder.pop();

        builder.push("board");
        BOARD_SIZE = builder
                .comment("How many open contracts each board offers (fewer if not enough items qualify). Contracts players",
                        "hold stay on the board as well until they're done or their time runs out.")
                .defineInRange("size", 8, 1, 64);
        REFRESH_DAYS = builder
                .comment("In-game days between boards. Sleeping counts; setting the time backwards doesn't. A new board",
                        "replaces the open and finished contracts; held ones stay.")
                .defineInRange("refresh_days", 3, 1, 365);
        builder.pop();

        builder.push("contract");
        DURATION_DAYS = builder
                .comment("In-game days a player has to deliver after claiming a contract. Then it expires: the units delivered",
                        "are paid for and deposit.refund_on_expiry of the deposit is returned.")
                .defineInRange("duration_days", 3, 1, 365);
        builder.pop();

        builder.push("claims");
        MAX_CLAIMS = builder
                .comment("How many contracts one player may hold at once.")
                .defineInRange("max_per_player", 2, 1, 64);
        builder.pop();

        builder.push("deposit");
        DEPOSIT_MODE = builder
                .comment("How the deposit taken when claiming is set.",
                        "PERCENT: deposit.amount percent of the contract's reward, rounded up.",
                        "FLAT: deposit.amount bits, whatever the contract pays.")
                .defineEnum("mode", ContractSettings.DepositMode.PERCENT);
        DEPOSIT_AMOUNT = builder
                .comment("The deposit: a percentage of the reward (PERCENT), or bits (FLAT; decimals are dropped).")
                .defineInRange("amount", 20.0, 0.0, 1_000_000_000.0);
        REFUND_ON_ABANDON = builder
                .comment("Percent of the deposit returned when the holder abandons a contract, rounded down.")
                .defineInRange("refund_on_abandon", 50.0, 0.0, 100.0);
        REFUND_ON_EXPIRY = builder
                .comment("Percent of the deposit returned when a held contract's time runs out, rounded down.")
                .defineInRange("refund_on_expiry", 0.0, 0.0, 100.0);
        builder.pop();

        builder.push("delivery");
        PARTIAL_ALLOWED = builder
                .comment("Items may be delivered in batches. When false, a delivery must hand in every unit the contract",
                        "still wants at once.")
                .define("partial_allowed", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ContractsConfig() {
    }

    /** Hands {@link ContractSettings} its values. Once, while the mod is constructed. */
    public static void install() {
        ContractSettings.install(new ContractSettings.Source() {
            @Override
            public boolean blockOnly() {
                return accessMode() == AccessMode.BLOCK_ONLY;
            }

            @Override
            public boolean contractsEnabled() {
                return value(ENABLED);
            }

            @Override
            public boolean clampToMarket() {
                return value(CLAMP_TO_MARKET);
            }

            @Override
            public int boardSize() {
                return value(BOARD_SIZE);
            }

            @Override
            public int refreshDays() {
                return value(REFRESH_DAYS);
            }

            @Override
            public int durationDays() {
                return value(DURATION_DAYS);
            }

            @Override
            public int maxClaims() {
                return value(MAX_CLAIMS);
            }

            @Override
            public ContractSettings.DepositMode depositMode() {
                return value(DEPOSIT_MODE);
            }

            @Override
            public double depositAmount() {
                return value(DEPOSIT_AMOUNT);
            }

            @Override
            public double refundOnAbandon() {
                return value(REFUND_ON_ABANDON);
            }

            @Override
            public double refundOnExpiry() {
                return value(REFUND_ON_EXPIRY);
            }

            @Override
            public boolean partialAllowed() {
                return value(PARTIAL_ALLOWED);
            }

            @Override
            public int maxQuantityPerRequest() {
                return value(MAX_QUANTITY_PER_REQUEST);
            }
        });
    }

    /** Whether players reach the contract board by command and block, or by block only. */
    public enum AccessMode { COMMAND_AND_BLOCK, BLOCK_ONLY }

    public static AccessMode accessMode() {
        return value(ACCESS_MODE);
    }

    /** A server config only loads when a server starts, so before then each value is its default. */
    private static <T> T value(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
