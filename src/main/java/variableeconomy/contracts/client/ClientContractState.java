package variableeconomy.contracts.client;

import variableeconomy.contracts.ContractsModule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import variableeconomy.contracts.network.ContractPayloads;
import variableeconomy.contracts.service.ContractResult;

import java.util.Locale;
import java.util.Map;

/**
 * What the client knows about the contract board, filled only from server packets. Display only: every request is
 * checked again on the server. Render thread only. Uses no client-only classes, so the network code can reference it
 * on both sides.
 */
public final class ClientContractState {
    /** The server's default limit on one delivery, used until the first state arrives. */
    private static final int DEFAULT_MAX_QUANTITY = 640;

    private static ContractPayloads.ContractState state = emptyState();
    /** The board, once the screen has received it. */
    @Nullable
    private static ContractPayloads.Contracts contracts;
    private static Component status = Component.empty();
    private static boolean statusIsError;
    private static int version;

    private ClientContractState() {
    }

    private static ContractPayloads.ContractState emptyState() {
        return new ContractPayloads.ContractState(0, Map.of(), DEFAULT_MAX_QUANTITY, true, true);
    }

    public static void applyState(ContractPayloads.ContractState received) {
        state = new ContractPayloads.ContractState(received.balance(), Map.copyOf(received.held()), received.maxQuantity(), received.enabled(),
                received.partialAllowed());
        version++;
    }

    public static void applyContracts(ContractPayloads.Contracts received) {
        contracts = received;
        version++;
    }

    public static void applyOutcome(ContractPayloads.ContractOutcome outcome) {
        ContractsModule.LOGGER.debug("Contract outcome: {}", outcome);
        Component itemName = BuiltInRegistries.ITEM.get(outcome.item()).getDescription();
        Component amount = PriceFormat.money(PriceFormat.amount(outcome.amount()));
        if (outcome.result() == ContractResult.OK) {
            status = switch (outcome.action()) {
                case CLAIM -> Component.translatable("screen.variable_economy_contracts.contracts.claimed", itemName, amount);
                case DELIVER -> outcome.amount() > 0
                        ? Component.translatable("screen.variable_economy_contracts.contracts.completed", itemName, amount)
                        : Component.translatable("screen.variable_economy_contracts.contracts.delivered", outcome.quantity(), itemName);
                case ABANDON -> Component.translatable("screen.variable_economy_contracts.contracts.abandoned", itemName, amount);
            };
            statusIsError = false;
        } else {
            status = Component.translatable("screen.variable_economy_contracts.result." + outcome.result().name().toLowerCase(Locale.ROOT));
            statusIsError = true;
        }
        version++;
    }

    /** When the board screen closes. */
    public static void clear() {
        state = emptyState();
        contracts = null;
        status = Component.empty();
        statusIsError = false;
        version++;
    }

    /** The board, or null until it's been received. */
    @Nullable
    public static ContractPayloads.Contracts contracts() {
        return contracts;
    }

    /** Whether the server offers contracts. */
    public static boolean enabled() {
        return state.enabled();
    }

    /** How many plain copies of {@code item} the main inventory holds; only items on the board are counted. */
    public static int held(ResourceLocation item) {
        return state.held().getOrDefault(item, 0);
    }

    /** The balance of the account the player uses. */
    public static long balance() {
        return state.balance();
    }

    /** Whether the balance looks enough for {@code total}. The server checks again. */
    public static boolean looksAffordable(long total) {
        return state.balance() >= total;
    }

    /** Whether deliveries may come in batches; when not, every remaining unit must go at once. */
    public static boolean partialAllowed() {
        return state.partialAllowed();
    }

    /** The server's limit on one delivery's quantity. */
    public static int maxQuantity() {
        return state.maxQuantity();
    }

    public static Component status() {
        return status;
    }

    public static boolean statusIsError() {
        return statusIsError;
    }

    /** Bumped on every change, so the screen knows when to refresh. */
    public static int version() {
        return version;
    }
}
