package variableeconomy.contracts;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;

/**
 * The contract board's server settings. They come from {@code config/variable_economy_contracts-server.toml},
 * whose keys are named below; {@link ContractsConfig} installs them when the mod is constructed. A server config only
 * loads when a server starts, so until then each value is the config's default.
 */
public final class ContractSettings {
    /** How the deposit is set (deposit.mode). */
    public enum DepositMode {
        /** A percentage of the contract's reward. */
        PERCENT,
        /** A fixed number of bits. */
        FLAT
    }

    /** Where the values come from. */
    public interface Source {
        /**
         * Whether players without operator permission must use a block, not commands (access_mode BLOCK_ONLY).
         *
         * @return the value
         */
        boolean blockOnly();

        /**
         * Whether contracts are offered (contracts.enabled).
         *
         * @return the value
         */
        boolean contractsEnabled();

        /**
         * Whether payouts are capped at the market's buy cost (contracts.clamp_to_market).
         *
         * @return the value
         */
        boolean clampToMarket();

        /**
         * How many open contracts a board offers (board.size).
         *
         * @return the value
         */
        int boardSize();

        /**
         * In-game days between boards (board.refresh_days).
         *
         * @return the value
         */
        int refreshDays();

        /**
         * In-game days to deliver after claiming (contract.duration_days).
         *
         * @return the value
         */
        int durationDays();

        /**
         * How many contracts one player may hold (claims.max_per_player).
         *
         * @return the value
         */
        int maxClaims();

        /**
         * How the deposit is set (deposit.mode).
         *
         * @return the value
         */
        DepositMode depositMode();

        /**
         * The deposit's percentage or bits (deposit.amount).
         *
         * @return the value
         */
        double depositAmount();

        /**
         * Percent of the deposit returned on abandoning (deposit.refund_on_abandon).
         *
         * @return the value
         */
        double refundOnAbandon();

        /**
         * Percent of the deposit returned at expiry (deposit.refund_on_expiry).
         *
         * @return the value
         */
        double refundOnExpiry();

        /**
         * Whether deliveries may come in batches (delivery.partial_allowed).
         *
         * @return the value
         */
        boolean partialAllowed();

        /**
         * The largest delivery one request may make (max_quantity_per_request).
         *
         * @return the value
         */
        int maxQuantityPerRequest();
    }

    private static volatile Source source;

    private ContractSettings() {
    }

    /** Installs where the values come from. Once, while the mod is constructed. */
    public static void install(Source values) {
        source = Objects.requireNonNull(values, "values");
    }

    private static Source source() {
        Source installed = source;
        if (installed == null) {
            throw new IllegalStateException("ContractSettings isn't installed yet");
        }
        return installed;
    }

    /** Whether players without operator permission must use a block, not commands (access_mode BLOCK_ONLY). */
    public static boolean blockOnly() {
        return source().blockOnly();
    }

    /** Whether contracts are offered (contracts.enabled). */
    public static boolean contractsEnabled() {
        return source().contractsEnabled();
    }

    /** Whether payouts are capped at the market's buy cost (contracts.clamp_to_market). */
    public static boolean clampToMarket() {
        return source().clampToMarket();
    }

    /** How many open contracts a board offers (board.size). */
    public static int boardSize() {
        return source().boardSize();
    }

    /** In-game days between boards (board.refresh_days). */
    public static int refreshDays() {
        return source().refreshDays();
    }

    /** In-game days to deliver after claiming (contract.duration_days). */
    public static int durationDays() {
        return source().durationDays();
    }

    /** How many contracts one player may hold (claims.max_per_player). */
    public static int maxClaims() {
        return source().maxClaims();
    }

    /** How the deposit is set (deposit.mode). */
    public static DepositMode depositMode() {
        return source().depositMode();
    }

    /** The deposit's percentage or bits (deposit.amount). */
    public static double depositAmount() {
        return source().depositAmount();
    }

    /** Percent of the deposit returned on abandoning (deposit.refund_on_abandon). */
    public static double refundOnAbandon() {
        return source().refundOnAbandon();
    }

    /** Percent of the deposit returned at expiry (deposit.refund_on_expiry). */
    public static double refundOnExpiry() {
        return source().refundOnExpiry();
    }

    /** Whether deliveries may come in batches (delivery.partial_allowed). */
    public static boolean partialAllowed() {
        return source().partialAllowed();
    }

    /** The largest delivery one request may make (max_quantity_per_request). */
    public static int maxQuantityPerRequest() {
        return source().maxQuantityPerRequest();
    }

    /** Whether {@code source} may use the contract board's commands: always for operators and the console, otherwise unless block-only. */
    public static boolean commandsAllowed(CommandSourceStack source) {
        return commandsAllowed(blockOnly(), source.hasPermission(Commands.LEVEL_GAMEMASTERS));
    }

    /** Whether {@code player} may use the contract board's commands. */
    public static boolean commandsAllowed(Player player) {
        return commandsAllowed(blockOnly(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS));
    }

    /** Operators always may; everyone else unless the server is block-only. */
    public static boolean commandsAllowed(boolean blockOnly, boolean operator) {
        return operator || !blockOnly;
    }
}
