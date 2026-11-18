package variableeconomy.contracts.definition;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One contract definition: what a contract asks for, how many, what it pays, how often it appears and who may claim
 * it. Loaded by {@link ContractDefinitions} from datapacks ({@code data/<namespace>/contract_definitions/}) and the
 * config folder ({@code config/variable_economy_contracts/definitions/}).
 *
 * @param id          the definition's ID, from its file's path
 * @param target      the item, item tag or market item class it asks for; a tag or class is a pool, and each contract
 *                    is for one item picked from it
 * @param minQuantity the fewest units one contract asks for
 * @param maxQuantity the most units one contract asks for; never below {@code minQuantity}
 * @param payout      what each unit pays
 * @param weight      how often it's picked for a board, relative to others; 0 never
 * @param stage       a ProgressiveStages stage a player needs to claim its contracts, if any
 * @param source      where it was loaded from, for admins
 */
public record ContractDefinition(ResourceLocation id, Target target, int minQuantity, int maxQuantity, Payout payout, int weight,
                                 Optional<String> stage, String source) {
    /** The most units one contract may ask for. */
    public static final int MAX_QUANTITY = 100_000;
    /** The highest weight. */
    public static final int MAX_WEIGHT = 1000;

    public ContractDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payout, "payout");
        Objects.requireNonNull(stage, "stage");
        if (minQuantity < 1 || maxQuantity < minQuantity) {
            throw new IllegalArgumentException("quantity " + minQuantity + "–" + maxQuantity);
        }
    }

    /** Whether it needs the market: a multiplier on market value, or a market item class. */
    public boolean needsMarket() {
        return payout.multiplier().isPresent() || target instanceof ClassTarget;
    }

    /** Whether its pool is one item, so it can give at most one contract per board. */
    public boolean singleItem() {
        return target instanceof ItemTarget;
    }

    /** What a definition asks for. */
    public sealed interface Target permits ItemTarget, TagTarget, ClassTarget {
        /** As written in a definition file, such as {@code minecraft:oak_log} or {@code #minecraft:logs}. */
        String describe();
    }

    /** One item. */
    public record ItemTarget(Item item) implements Target {
        @Override
        public String describe() {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
    }

    /** Any item in a tag. */
    public record TagTarget(TagKey<Item> tag) implements Target {
        @Override
        public String describe() {
            return "#" + tag.location();
        }
    }

    /** Any item the market's catalog gives this class. */
    public record ClassTarget(ResourceLocation itemClass) implements Target {
        @Override
        public String describe() {
            return "market class " + itemClass;
        }
    }

    /**
     * What each unit pays: a fixed amount in bits ({@code perUnit}, decimals allowed; a contract's total is rounded down
     * once), or a multiple of the item's market base price ({@code multiplier}). Exactly one is set.
     */
    public record Payout(Optional<BigDecimal> perUnit, Optional<BigDecimal> multiplier) {
        public Payout {
            if (perUnit.isPresent() == multiplier.isPresent()) {
                throw new IllegalArgumentException("a payout is either per_unit or multiplier");
            }
        }

        public static Payout fixed(BigDecimal perUnit) {
            return new Payout(Optional.of(perUnit), Optional.empty());
        }

        public static Payout times(BigDecimal multiplier) {
            return new Payout(Optional.empty(), Optional.of(multiplier));
        }

        /** As written in a definition file, for admins. */
        public String describe() {
            return perUnit.map(p -> p.stripTrailingZeros().toPlainString() + " per unit")
                    .orElseGet(() -> multiplier.orElseThrow().stripTrailingZeros().toPlainString() + " × market base price");
        }
    }
}
