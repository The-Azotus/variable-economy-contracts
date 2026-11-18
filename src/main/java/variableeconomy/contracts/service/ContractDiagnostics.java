package variableeconomy.contracts.service;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import variableeconomy.contracts.ContractSettings;
import variableeconomy.contracts.ContractsModule;
import variableeconomy.contracts.compat.MarketBridge;
import variableeconomy.contracts.definition.ContractDefinition;
import variableeconomy.contracts.definition.ContractDefinitions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;

/**
 * Checks the contract definitions against the market, one line per definition at most: logged at server start and
 * after every reload (with the market, after its catalog loads), and listed by {@code /contracts admin definitions}.
 * <ul>
 *   <li>Without the market, each definition that needs it (a multiplier, or a market class) is off.</li>
 *   <li>A tag with no items, or no item that isn't a coin. (A market class with no items isn't warned about: a catalog
 *       needn't use every class, and the built-in definitions name them all.)</li>
 *   <li>With the market, a definition that could pay more for some of its items than buying them costs: capped
 *       while {@code contracts.clamp_to_market} is on, and a way for players to profit while it's off.</li>
 * </ul>
 * A capped definition is logged at info level, not as a warning: the cap is working as it should, and the built-in
 * definitions (1.25 × base) are capped for many items on every server. Every other line is a warning.
 * Server thread only.
 */
public final class ContractDiagnostics {
    private ContractDiagnostics() {
    }

    /** One line about a definition: a warning, or (for a capped one) a note. */
    private record Finding(String message, boolean warning) {
    }

    /** Logs the lines for the loaded definitions (warnings, and capped ones at info level), then one summary line. */
    public static void log(MinecraftServer server) {
        Collection<ContractDefinition> definitions = ContractDefinitions.INSTANCE.all().values();
        List<Finding> findings = findings(server, definitions);
        long warnings = 0;
        for (Finding finding : findings) {
            if (finding.warning()) {
                ContractsModule.LOGGER.warn(finding.message());
                warnings++;
            } else {
                ContractsModule.LOGGER.info(finding.message());
            }
        }
        long usable = definitions.stream().filter(d -> d.weight() > 0 && (MarketBridge.available() || !d.needsMarket())).count();
        ContractsModule.LOGGER.info("Contract definitions: {} loaded, {} in use, {} warning(s){}", definitions.size(), usable, warnings,
                warnings == 0 ? "" : " (listed above, and by /contracts admin definitions)");
    }

    /** Every line about these definitions, warnings and capped ones alike, as {@code /contracts admin definitions} lists them. */
    public static List<String> run(MinecraftServer server, Collection<ContractDefinition> definitions) {
        return findings(server, definitions).stream().map(Finding::message).toList();
    }

    private static List<Finding> findings(MinecraftServer server, Collection<ContractDefinition> definitions) {
        List<Finding> warnings = new ArrayList<>();
        for (ContractDefinition definition : definitions) {
            if (definition.weight() == 0) {
                continue;
            }
            if (definition.needsMarket() && !MarketBridge.available()) {
                warnings.add(new Finding("Contract definition %s %s, and the market add-on isn't installed, so it's off".formatted(definition.id(),
                        definition.payout().multiplier().isPresent() ? "pays a multiplier on market value" : "asks for a market class"), true));
                continue;
            }
            List<Item> items = ContractService.items(server, definition);
            if (items.isEmpty()) {
                if (definition.target() instanceof ContractDefinition.ClassTarget) {
                    continue;
                }
                warnings.add(new Finding("Contract definition %s asks for %s, which holds no items, so it offers nothing".formatted(definition.id(),
                        definition.target().describe()), true));
                continue;
            }
            List<Item> over = items.stream().filter(item -> paysMoreThanBuying(server, definition, item)).toList();
            if (!over.isEmpty()) {
                String which = describe(over, items.size());
                boolean clamped = ContractSettings.clampToMarket();
                warnings.add(new Finding(clamped
                        ? "Contract definition %s would pay more than buying %s from the market costs; its payouts are capped below that (contracts.clamp_to_market)"
                        .formatted(definition.id(), which)
                        : "Contract definition %s pays more than buying %s from the market costs, and contracts.clamp_to_market is off: players can profit by buying them to deliver"
                        .formatted(definition.id(), which), !clamped));
            }
        }
        return warnings;
    }

    /**
     * Whether a contract from this definition for its smallest or largest size, uncapped, would pay at least what
     * buying those units from the market could cost.
     */
    static boolean paysMoreThanBuying(MinecraftServer server, ContractDefinition definition, Item item) {
        var unitReward = ContractService.unitReward(server, definition, item);
        if (unitReward.isEmpty()) {
            return false;
        }
        for (int units : new int[] {definition.minQuantity(), definition.maxQuantity()}) {
            OptionalLong lowest = MarketBridge.lowestBuyCost(server, item, units);
            if (lowest.isPresent() && ContractPricing.payout(unitReward.get(), OptionalLong.empty(), units) >= lowest.getAsLong()) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<Item> items, int poolSize) {
        String first = BuiltInRegistries.ITEM.getKey(items.get(0)).toString();
        if (poolSize == 1) {
            return first;
        }
        return items.size() == 1 ? "1 of its %d items (%s)".formatted(poolSize, first)
                : "%d of its %d items (such as %s)".formatted(items.size(), poolSize, first);
    }
}
