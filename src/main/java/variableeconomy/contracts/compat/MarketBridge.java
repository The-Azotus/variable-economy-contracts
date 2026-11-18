package variableeconomy.contracts.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import variableeconomy.market.api.MarketApi;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * The only class in the contracts add-on allowed to use the market add-on, an optional dependency, and only through
 * its {@code api} package. Without the market nothing here touches its classes: they're only referenced from
 * {@link Present}. Contracts reads from it what an item is worth, its item class (for definitions that ask for a
 * class), and the least buying units could ever cost, which caps every payout so buying from the market to deliver
 * can never profit.
 * <p>
 * Server thread only.
 */
public final class MarketBridge {
    /** The market add-on's mod id. */
    public static final String MARKET_ID = "variable_economy_market";
    private static final boolean AVAILABLE = ModList.get().isLoaded(MARKET_ID);

    private MarketBridge() {
    }

    /**
     * What the board needs of an item's market terms.
     *
     * @param basePrice its base price
     * @param canSell   whether the market buys it
     * @param itemClass its item class, if the catalog gives it one
     */
    public record Value(long basePrice, boolean canSell, Optional<ResourceLocation> itemClass) {
    }

    /** Whether the market is installed. Without it, definitions paying a multiplier or asking for a class are off. */
    public static boolean available() {
        return AVAILABLE;
    }

    /** Every item the market lists; empty without the market. */
    public static List<Item> listedItems(MinecraftServer server) {
        return available() ? Present.listedItems(server) : List.of();
    }

    /** The item's base price, whether the market buys it, and its class; empty if it isn't listed. */
    public static Optional<Value> value(MinecraftServer server, Item item) {
        return available() ? Present.value(server, item) : Optional.empty();
    }

    /** What selling {@code quantity} units at the item's base price would pay; empty if the market doesn't buy it. */
    public static OptionalLong normalSellTotal(MinecraftServer server, Item item, int quantity) {
        return available() ? Present.normalSellTotal(server, item, quantity) : OptionalLong.empty();
    }

    /** The least buying {@code quantity} units from the market could ever cost; empty when the market sets no bound. */
    public static OptionalLong lowestBuyCost(MinecraftServer server, Item item, int quantity) {
        return available() ? Present.lowestBuyCost(server, item, quantity) : OptionalLong.empty();
    }

    /**
     * Calls {@code listener} whenever the market's catalog has loaded or changed (at server start, after {@code
     * /reload}, after admin edits). Does nothing without the market. Once, while the mod is constructed.
     */
    public static void onCatalogReloaded(Consumer<MinecraftServer> listener) {
        if (available()) {
            Present.onCatalogReloaded(listener);
        }
    }

    /** Everything that touches the market's classes. */
    private static final class Present {
        static void onCatalogReloaded(Consumer<MinecraftServer> listener) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (variableeconomy.market.api.event.CatalogReloadedEvent event) -> listener.accept(event.getServer()));
        }

        static List<Item> listedItems(MinecraftServer server) {
            return MarketApi.prices().listedItems(server);
        }

        static Optional<Value> value(MinecraftServer server, Item item) {
            return MarketApi.prices().quote(server, item).map(quote -> new Value(quote.basePrice(), quote.canSell(), quote.itemClass()));
        }

        static OptionalLong normalSellTotal(MinecraftServer server, Item item, int quantity) {
            return MarketApi.prices().normalSellTotal(server, item, quantity);
        }

        static OptionalLong lowestBuyCost(MinecraftServer server, Item item, int quantity) {
            return MarketApi.prices().lowestBuyCost(server, item, quantity);
        }
    }
}
