package variableeconomy.contracts.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.conditions.ICondition;
import variableeconomy.contracts.ContractsModule;
import variableeconomy.core.api.CoreApi;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import com.mojang.serialization.DynamicOps;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Every contract definition, loaded at server start and on {@code /reload}: from datapacks at
 * {@code data/<namespace>/contract_definitions/<path>.json}, then from {@code
 * config/variable_economy_contracts/definitions/<namespace>/<path>.json}, which sits above every datapack. A file
 * with the same ID replaces the one below it; NeoForge's {@code neoforge:conditions} work in every file. A file that
 * can't be used is skipped with one warning naming it and why.
 * <p>
 * A file's fields:
 * <ul>
 *   <li>exactly one of {@code item} (an item ID), {@code tag} (an item tag, with or without {@code #}) or
 *       {@code market_class} (a market item class; a name without a namespace means {@code variable_economy:});</li>
 *   <li>{@code quantity}: a whole number, or {@code {"min": .., "max": ..}}, 1 to 100,000;</li>
 *   <li>{@code payout}: {@code {"per_unit": <bits>}} (decimals allowed) or {@code {"multiplier": <number>}} (times the
 *       item's market base price);</li>
 *   <li>optional {@code weight} (default 1, 0 to 1000; 0 switches it off), {@code stage} (a ProgressiveStages stage)
 *       and {@code description} (for admins).</li>
 * </ul>
 */
public final class ContractDefinitions extends SimplePreparableReloadListener<ContractDefinitions.Prepared> {
    public static final ContractDefinitions INSTANCE = new ContractDefinitions();
    /** Where definitions live in a datapack. */
    public static final String FOLDER = "contract_definitions";
    private static final FileToIdConverter FILES = FileToIdConverter.json(FOLDER);
    private static final Set<String> FIELDS = Set.of("item", "tag", "market_class", "quantity", "payout", "weight", "stage",
            "description", "neoforge:conditions");
    /** The namespace of the market's built-in item classes, for class names given without one. */
    private static final String MARKET_CLASS_NAMESPACE = "variable_economy";
    private static final BigDecimal MAX_PER_UNIT = BigDecimal.valueOf(1_000_000_000_000L);
    private static final BigDecimal MAX_MULTIPLIER = BigDecimal.valueOf(100);

    private volatile Map<ResourceLocation, ContractDefinition> definitions = Map.of();
    private volatile List<String> problems = List.of();

    private ContractDefinitions() {
    }

    /** A definition file as read, before it's checked. */
    public record RawFile(ResourceLocation id, JsonElement json, String source) {
    }

    /** What {@link #prepare} found: the files to use, highest priority already chosen, and what couldn't be read. */
    public record Prepared(Map<ResourceLocation, RawFile> files, List<String> problems) {
    }

    /** Every definition, by ID. */
    public Map<ResourceLocation, ContractDefinition> all() {
        return definitions;
    }

    public Optional<ContractDefinition> get(ResourceLocation id) {
        return Optional.ofNullable(definitions.get(id));
    }

    /** What the last load skipped, and why. */
    public List<String> problems() {
        return problems;
    }

    /** Replaces every definition, for GameTests. Returns what was there, to put back. */
    public Map<ResourceLocation, ContractDefinition> replaceForTesting(Map<ResourceLocation, ContractDefinition> replacement) {
        Map<ResourceLocation, ContractDefinition> before = definitions;
        definitions = Collections.unmodifiableMap(new TreeMap<>(replacement));
        return before;
    }

    /** {@code config/variable_economy_contracts/definitions/}: definitions that apply to every world. */
    public static Path configFolder() {
        return FMLPaths.CONFIGDIR.get().resolve(ContractsModule.MOD_ID).resolve("definitions");
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<ResourceLocation, RawFile> files = new TreeMap<>();
        List<String> unreadable = new ArrayList<>();
        // One resource per ID: the highest-priority pack's.
        for (Map.Entry<ResourceLocation, Resource> file : FILES.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation id = FILES.fileToId(file.getKey());
            try (Reader reader = file.getValue().openAsReader()) {
                files.put(id, new RawFile(id, JsonParser.parseReader(reader), "pack " + file.getValue().sourcePackId()));
            } catch (Exception e) {
                unreadable.add("Contract definition %s in pack %s can't be read (%s); skipped".formatted(id,
                        file.getValue().sourcePackId(), e.getMessage()));
            }
        }
        readConfigFolder(configFolder(), files, unreadable);
        return new Prepared(files, unreadable);
    }

    /** Adds the config folder's files over the packs': {@code <root>/<namespace>/<path>.json} is {@code namespace:path}. */
    public static void readConfigFolder(Path root, Map<ResourceLocation, RawFile> files, List<String> unreadable) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            unreadable.add("The folder %s can't be created (%s)".formatted(root, e.getMessage()));
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".json")).sorted().toList()) {
                Path relative = root.relativize(path);
                String name = relative.toString().replace('\\', '/');
                int slash = name.indexOf('/');
                ResourceLocation id = slash < 0 ? null
                        : ResourceLocation.tryBuild(name.substring(0, slash), name.substring(slash + 1, name.length() - ".json".length()));
                if (id == null) {
                    unreadable.add(("Contract definition file config/%s/definitions/%s isn't at <namespace>/<path>.json in lower case; "
                            + "skipped").formatted(ContractsModule.MOD_ID, name));
                    continue;
                }
                try (Reader reader = Files.newBufferedReader(path)) {
                    files.put(id, new RawFile(id, JsonParser.parseReader(reader), "config folder"));
                } catch (Exception e) {
                    unreadable.add("Contract definition %s in the config folder can't be read (%s); skipped".formatted(id, e.getMessage()));
                }
            }
        } catch (IOException e) {
            unreadable.add("The folder %s can't be read (%s)".formatted(root, e.getMessage()));
        }
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        List<String> found = new ArrayList<>(prepared.problems());
        Map<ResourceLocation, ContractDefinition> loaded = read(prepared.files().values(), makeConditionalOps(), found);
        definitions = Collections.unmodifiableMap(loaded);
        problems = List.copyOf(found);
        found.forEach(ContractsModule.LOGGER::warn);
        ContractsModule.LOGGER.info("Loaded {} contract definition(s){}", loaded.size(),
                found.isEmpty() ? "" : "; " + found.size() + " problem(s), listed above");
    }

    /**
     * The definitions in these files whose conditions hold, by ID. A file that can't be used, its conditions
     * included, is skipped and said why in {@code problems}.
     */
    public static Map<ResourceLocation, ContractDefinition> read(Collection<RawFile> files, DynamicOps<JsonElement> ops, List<String> problems) {
        Map<ResourceLocation, ContractDefinition> loaded = new TreeMap<>();
        for (RawFile file : files) {
            try {
                // A condition that can't be read (an unknown type, a missing field) throws; the file is skipped like any other bad one.
                if (!ICondition.conditionsMatched(ops, file.json())) {
                    continue;
                }
                ContractDefinition definition = parse(file.id(), file.json(), file.source(), problems);
                loaded.put(definition.id(), definition);
            } catch (RuntimeException e) {
                problems.add("Contract definition %s (%s): %s; skipped".formatted(file.id(), file.source(), e.getMessage()));
            }
        }
        return loaded;
    }

    /**
     * A definition from its JSON. Throws {@link IllegalArgumentException} saying what's wrong when it can't be used;
     * adds to {@code warnings} what it ignores.
     */
    public static ContractDefinition parse(ResourceLocation id, JsonElement json, String source, List<String> warnings) {
        if (!json.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject o = json.getAsJsonObject();
        for (String field : o.keySet()) {
            if (!FIELDS.contains(field)) {
                warnings.add("Contract definition %s (%s): unknown field \"%s\" ignored".formatted(id, source, field));
            }
        }
        ContractDefinition.Target target = target(o);
        int[] quantity = quantity(o.get("quantity"));
        ContractDefinition.Payout payout = payout(o.get("payout"));
        int weight = o.has("weight") ? wholeNumber(o.get("weight"), "weight", 0, ContractDefinition.MAX_WEIGHT) : 1;
        Optional<String> stage = Optional.empty();
        if (o.has("stage")) {
            String name = string(o.get("stage"), "stage");
            if (name.isBlank()) {
                throw new IllegalArgumentException("\"stage\" is empty");
            }
            stage = Optional.of(name);
        }
        return new ContractDefinition(id, target, quantity[0], quantity[1], payout, weight, stage, source);
    }

    private static ContractDefinition.Target target(JsonObject o) {
        int given = (o.has("item") ? 1 : 0) + (o.has("tag") ? 1 : 0) + (o.has("market_class") ? 1 : 0);
        if (given != 1) {
            throw new IllegalArgumentException("needs exactly one of \"item\", \"tag\" or \"market_class\"");
        }
        if (o.has("item")) {
            ResourceLocation itemId = location(o.get("item"), "item");
            Item item = BuiltInRegistries.ITEM.getOptional(itemId).filter(i -> i != Items.AIR)
                    .orElseThrow(() -> new IllegalArgumentException("unknown item " + itemId));
            if (CoreApi.isCoin(item)) {
                throw new IllegalArgumentException(itemId + " is a coin, and coins are never contract items: paying for them would create money");
            }
            return new ContractDefinition.ItemTarget(item);
        }
        if (o.has("tag")) {
            String name = string(o.get("tag"), "tag");
            ResourceLocation tag = ResourceLocation.tryParse(name.startsWith("#") ? name.substring(1) : name);
            if (tag == null) {
                throw new IllegalArgumentException("\"tag\" isn't a tag ID: " + name);
            }
            return new ContractDefinition.TagTarget(TagKey.create(Registries.ITEM, tag));
        }
        String name = string(o.get("market_class"), "market_class");
        ResourceLocation itemClass = name.contains(":") ? ResourceLocation.tryParse(name)
                : ResourceLocation.tryBuild(MARKET_CLASS_NAMESPACE, name);
        if (itemClass == null) {
            throw new IllegalArgumentException("\"market_class\" isn't a class ID: " + name);
        }
        return new ContractDefinition.ClassTarget(itemClass);
    }

    /** {@code [min, max]}. */
    private static int[] quantity(JsonElement json) {
        if (json == null) {
            throw new IllegalArgumentException("needs \"quantity\"");
        }
        if (json.isJsonObject()) {
            JsonObject range = json.getAsJsonObject();
            if (!range.has("min") || !range.has("max")) {
                throw new IllegalArgumentException("\"quantity\" needs \"min\" and \"max\"");
            }
            int min = wholeNumber(range.get("min"), "quantity min", 1, ContractDefinition.MAX_QUANTITY);
            int max = wholeNumber(range.get("max"), "quantity max", 1, ContractDefinition.MAX_QUANTITY);
            if (min > max) {
                throw new IllegalArgumentException("quantity min (" + min + ") is above max (" + max + ")");
            }
            return new int[] {min, max};
        }
        int exact = wholeNumber(json, "quantity", 1, ContractDefinition.MAX_QUANTITY);
        return new int[] {exact, exact};
    }

    private static ContractDefinition.Payout payout(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            throw new IllegalArgumentException("needs \"payout\": {\"per_unit\": <bits>} or {\"multiplier\": <number>}");
        }
        JsonObject p = json.getAsJsonObject();
        if (p.has("per_unit") == p.has("multiplier")) {
            throw new IllegalArgumentException("\"payout\" needs exactly one of \"per_unit\" or \"multiplier\"");
        }
        if (p.has("per_unit")) {
            return ContractDefinition.Payout.fixed(positive(p.get("per_unit"), "payout per_unit", MAX_PER_UNIT));
        }
        return ContractDefinition.Payout.times(positive(p.get("multiplier"), "payout multiplier", MAX_MULTIPLIER));
    }

    private static BigDecimal positive(JsonElement json, String name, BigDecimal max) {
        BigDecimal value = number(json, name);
        if (value.signum() <= 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("%s must be above 0 and at most %s, not %s".formatted(name, max.toPlainString(),
                    value.toPlainString()));
        }
        return value;
    }

    private static int wholeNumber(JsonElement json, String name, int min, int max) {
        BigDecimal value = number(json, name);
        if (value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.valueOf(min)) < 0
                || value.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new IllegalArgumentException("%s must be a whole number from %d to %d, not %s".formatted(name, min, max,
                    value.toPlainString()));
        }
        return value.intValueExact();
    }

    private static BigDecimal number(JsonElement json, String name) {
        if (json instanceof JsonPrimitive primitive && primitive.isNumber()) {
            try {
                return new BigDecimal(primitive.getAsString());
            } catch (NumberFormatException e) {
                // Reported below.
            }
        }
        throw new IllegalArgumentException(name + " must be a number");
    }

    private static String string(JsonElement json, String name) {
        if (json instanceof JsonPrimitive primitive && primitive.isString()) {
            return primitive.getAsString();
        }
        throw new IllegalArgumentException("\"" + name + "\" must be a string");
    }

    private static ResourceLocation location(JsonElement json, String name) {
        String value = string(json, name);
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException("\"" + name + "\" isn't an ID: " + value);
        }
        return id;
    }
}
