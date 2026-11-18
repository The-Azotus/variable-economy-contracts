package variableeconomy.contracts.gametest;

import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import variableeconomy.contracts.ContractSettings;
import variableeconomy.contracts.ContractsModule;
import variableeconomy.contracts.data.ContractBoard;
import variableeconomy.contracts.definition.ContractDefinition;
import variableeconomy.contracts.definition.ContractDefinitions;
import variableeconomy.contracts.service.ContractPricing;
import variableeconomy.contracts.service.ContractService;
import variableeconomy.core.api.CoreApi;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongUnaryOperator;

import static variableeconomy.contracts.gametest.CoreTestSupport.*;

/**
 * Contract definitions: reading them, the config folder above the datapacks, their pools, and boards built from them.
 * The boards use items no market lists (barriers, structure voids, lights), so their payouts are the same with or
 * without the market.
 */
@GameTestHolder(ContractsModule.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DefinitionTests {
    private DefinitionTests() {
    }

    private static final LongUnaryOperator FIFTH = r -> ContractPricing.deposit(r, ContractSettings.DepositMode.PERCENT, 20);

    private static ContractDefinition parse(String json, List<String> warnings) {
        return ContractDefinitions.parse(rl("test"), JsonParser.parseString(json), "test", warnings);
    }

    private static String refusal(String json) {
        try {
            parse(json, new ArrayList<>());
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    @GameTest(template = EMPTY)
    public static void definitionsAreReadAndChecked(GameTestHelper helper) {
        List<String> warnings = new ArrayList<>();
        ContractDefinition logs = parse("""
                { "tag": "#minecraft:logs", "quantity": { "min": 8, "max": 32 }, "payout": { "per_unit": 2.5 }, "weight": 3,
                  "stage": "iron_age", "description": "notes", "colour": "red" }""", warnings);
        helper.assertValueEqual(logs.target(), new ContractDefinition.TagTarget(ItemTags.LOGS), "a tag, # allowed");
        helper.assertValueEqual(logs.minQuantity(), 8, "min");
        helper.assertValueEqual(logs.maxQuantity(), 32, "max");
        helper.assertValueEqual(logs.payout(), ContractDefinition.Payout.fixed(new BigDecimal("2.5")), "a fixed payout with decimals");
        helper.assertValueEqual(logs.weight(), 3, "weight");
        helper.assertValueEqual(logs.stage(), Optional.of("iron_age"), "stage");
        helper.assertTrue(!logs.needsMarket(), "fixed payouts for a tag don't need the market");
        helper.assertTrue(warnings.size() == 1 && warnings.get(0).contains("\"colour\""), "an unknown field is warned about: " + warnings);

        ContractDefinition iron = parse("""
                { "item": "minecraft:iron_ingot", "quantity": 16, "payout": { "multiplier": 1.5 } }""", warnings);
        helper.assertValueEqual(iron.target(), new ContractDefinition.ItemTarget(Items.IRON_INGOT), "an item");
        helper.assertTrue(iron.minQuantity() == 16 && iron.maxQuantity() == 16, "a single quantity");
        helper.assertValueEqual(iron.weight(), 1, "weight defaults to 1");
        helper.assertTrue(iron.needsMarket() && iron.singleItem(), "a multiplier needs the market");

        ContractDefinition farm = parse("""
                { "market_class": "farmable", "quantity": { "min": 1, "max": 1 }, "payout": { "multiplier": 1 } }""", warnings);
        helper.assertValueEqual(farm.target(), new ContractDefinition.ClassTarget(ResourceLocation.fromNamespaceAndPath("variable_economy",
                "farmable")), "a class name without a namespace is the market's");

        Item coin = BuiltInRegistries.ITEM.stream().filter(CoreApi::isCoin).findFirst().orElseThrow();
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("{ \"quantity\": 1, \"payout\": { \"per_unit\": 1 } }", "exactly one of");
        refused.put("{ \"item\": \"minecraft:stone\", \"tag\": \"minecraft:logs\", \"quantity\": 1, \"payout\": { \"per_unit\": 1 } }", "exactly one of");
        refused.put("{ \"item\": \"minecraft:no_such_item\", \"quantity\": 1, \"payout\": { \"per_unit\": 1 } }", "unknown item");
        refused.put("{ \"item\": \"" + id(coin) + "\", \"quantity\": 1, \"payout\": { \"per_unit\": 1 } }", "coin");
        refused.put("{ \"item\": \"minecraft:stone\", \"payout\": { \"per_unit\": 1 } }", "needs \"quantity\"");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": { \"min\": 9, \"max\": 8 }, \"payout\": { \"per_unit\": 1 } }", "above max");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 0, \"payout\": { \"per_unit\": 1 } }", "quantity must be");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 2.5, \"payout\": { \"per_unit\": 1 } }", "whole number");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 1, \"payout\": { \"per_unit\": 1, \"multiplier\": 1 } }", "exactly one of \"per_unit\"");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 1, \"payout\": { \"multiplier\": 0 } }", "above 0");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 1, \"payout\": { \"multiplier\": 101 } }", "at most 100");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 1, \"payout\": 5 }", "needs \"payout\"");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 1, \"payout\": { \"per_unit\": 1 }, \"weight\": 1001 }", "weight must be");
        refused.put("{ \"item\": \"minecraft:stone\", \"quantity\": 1, \"payout\": { \"per_unit\": 1 }, \"stage\": \"\" }", "\"stage\" is empty");
        refused.put("[]", "not a JSON object");
        refused.forEach((json, expected) -> {
            String why = refusal(json);
            helper.assertTrue(why.contains(expected), "refused with \"" + expected + "\": " + json + " → " + why);
        });
        helper.succeed();
    }

    /** A file whose conditions can't be read is skipped with a warning, as any other bad file, and loading goes on. */
    @GameTest(template = EMPTY)
    public static void aBadConditionSkipsOnlyItsFile(GameTestHelper helper) {
        String body = "\"item\": \"minecraft:stone\", \"quantity\": 1, \"payout\": { \"per_unit\": 1 }";
        List<ContractDefinitions.RawFile> files = List.of(
                new ContractDefinitions.RawFile(rl("typo"), JsonParser.parseString("{ " + body
                        + ", \"neoforge:conditions\": [ { \"type\": \"neoforge:mod_loded\", \"modid\": \"minecraft\" } ] }"), "test"),
                new ContractDefinitions.RawFile(rl("off"), JsonParser.parseString("{ " + body
                        + ", \"neoforge:conditions\": [ { \"type\": \"neoforge:mod_loaded\", \"modid\": \"no_such_mod\" } ] }"), "test"),
                new ContractDefinitions.RawFile(rl("fine"), JsonParser.parseString("{ " + body + " }"), "test"));
        List<String> problems = new ArrayList<>();
        Map<ResourceLocation, ContractDefinition> loaded = ContractDefinitions.read(files, com.mojang.serialization.JsonOps.INSTANCE, problems);
        helper.assertValueEqual(loaded.keySet(), Set.of(rl("fine")), "the good file loads; the one whose condition fails quietly doesn't");
        helper.assertTrue(problems.size() == 1 && problems.get(0).contains(rl("typo").toString()), "the typo is reported: " + problems);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theConfigFolderSitsAboveThePacks(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("contract_definitions");
        try {
            Files.createDirectories(root.resolve("mypack/sub"));
            Files.writeString(root.resolve("mypack/sub/logs.json"), "{ \"tag\": \"minecraft:logs\", \"quantity\": 4, \"payout\": { \"per_unit\": 9 } }");
            Files.writeString(root.resolve("loose.json"), "{}");
            Files.writeString(root.resolve("mypack/Bad Name.json"), "{}");
            Map<ResourceLocation, ContractDefinitions.RawFile> files = new TreeMap<>();
            ResourceLocation logs = ResourceLocation.fromNamespaceAndPath("mypack", "sub/logs");
            files.put(logs, new ContractDefinitions.RawFile(logs, JsonParser.parseString("{}"), "pack lower"));
            List<String> problems = new ArrayList<>();
            ContractDefinitions.readConfigFolder(root, files, problems);
            helper.assertValueEqual(files.get(logs).source(), "config folder", "the folder's file replaces the pack's with the same ID");
            helper.assertValueEqual(files.size(), 1, "files outside <namespace>/<path>.json are skipped");
            helper.assertValueEqual(problems.size(), 2, "and each reported: " + problems);
        } finally {
            deleteTree(root);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void poolsHoldTheirItemsAndNeverCoins(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContractDefinition logs = definition("logs", new ContractDefinition.TagTarget(ItemTags.LOGS), 1, 1, "1", 1);
        List<Item> items = ContractService.items(server, logs);
        helper.assertTrue(items.contains(Items.OAK_LOG) && items.contains(Items.BIRCH_LOG), "the tag's items");
        helper.assertTrue(ContractService.asksFor(server, logs, Items.SPRUCE_LOG) && !ContractService.asksFor(server, logs, Items.STONE),
                "membership");
        Item coin = BuiltInRegistries.ITEM.stream().filter(CoreApi::isCoin).findFirst().orElseThrow();
        ContractDefinition coins = definition("coin", new ContractDefinition.ItemTarget(coin), 1, 1, "1", 1);
        helper.assertTrue(ContractService.items(server, coins).isEmpty(), "a coin is never in a pool");
        helper.assertTrue(!ContractService.asksFor(server, coins, coin), "nor asked for");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardsFollowTheDefinitions(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        // A pool of three unlisted items, a single item, and one switched off.
        ContractDefinition pool = new ContractDefinition(rl("pool"), new ContractDefinition.TagTarget(
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, rl("unused_tag"))), 1, 1,
                ContractDefinition.Payout.fixed(BigDecimal.ONE), 1, Optional.empty(), "test");
        ContractDefinition barrier = definition("barrier", new ContractDefinition.ItemTarget(Items.BARRIER), 100, 300, "1.5", 5);
        ContractDefinition off = definition("off", new ContractDefinition.ItemTarget(Items.STRUCTURE_VOID), 1, 1, "1", 0);
        ContractDefinition light = definition("light", new ContractDefinition.ItemTarget(Items.LIGHT), 3, 3, "2", 1);
        int[] ids = {1};
        Set<ResourceLocation> seen = new HashSet<>();
        for (int seed = 0; seed < 30; seed++) {
            List<ContractBoard.Contract> board = ContractService.generate(server, RandomSource.create(seed), 8, FIFTH, () -> ids[0]++,
                    List.of(pool, barrier, off, light));
            helper.assertValueEqual(board.stream().map(ContractBoard.Contract::item).distinct().count(), (long) board.size(), "no item twice");
            helper.assertValueEqual(board.size(), 2, "the empty tag and the switched-off definition give nothing: " + board);
            for (ContractBoard.Contract c : board) {
                seen.add(c.definition());
                if (c.definition().equals(barrier.id())) {
                    helper.assertTrue(c.units() >= 96 && c.units() <= 300 && c.units() % 16 == 0 || c.units() == 100,
                            "sizes from 64 up round down to 16s, never below the minimum: " + c.units());
                    helper.assertValueEqual(c.reward(), BigDecimal.valueOf(c.units()).multiply(new BigDecimal("1.5")).longValue(),
                            "the fixed payout per unit, rounded down once");
                } else {
                    helper.assertValueEqual(c.reward(), 6L, "3 lights at 2");
                }
                helper.assertValueEqual(c.deposit(), ContractPricing.deposit(c.reward(), ContractSettings.DepositMode.PERCENT, 20), "deposit");
                helper.assertValueEqual(c.status(), ContractBoard.Status.OPEN, "open");
            }
        }
        helper.assertValueEqual(seen, Set.of(barrier.id(), light.id()), "both usable definitions appear");
        helper.assertValueEqual(ContractService.whyNotOffered(server, off, Items.STRUCTURE_VOID, 1, FIFTH), Optional.of("its weight is 0"),
                "the reason given for one switched off");

        // The board itself reads the live definitions, which tests swap for their own.
        Map<ResourceLocation, ContractDefinition> before = ContractDefinitions.INSTANCE.replaceForTesting(Map.of(light.id(), light));
        try {
            ContractBoard live = new ContractBoard();
            live.observe(ContractBoard.worldDay(server));
            ContractService.refresh(server, live, RandomSource.create(1));
            helper.assertValueEqual(live.contracts().stream().map(ContractBoard.Contract::definition).toList(), List.of(light.id()),
                    "one contract from the one definition");
        } finally {
            ContractDefinitions.INSTANCE.replaceForTesting(before);
        }
        helper.succeed();
    }

    /** Without the market, each definition that needs it is warned about once; with it, those load as usual. */
    @GameTest(template = EMPTY)
    public static void diagnosticsWarnOncePerDefinition(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContractDefinition times = new ContractDefinition(rl("times"), new ContractDefinition.ItemTarget(Items.BARRIER), 1, 1,
                ContractDefinition.Payout.times(BigDecimal.ONE), 1, Optional.empty(), "test");
        ContractDefinition itemClass = new ContractDefinition(rl("class"), new ContractDefinition.ClassTarget(rl("no_such_class")), 1, 1,
                ContractDefinition.Payout.fixed(BigDecimal.ONE), 1, Optional.empty(), "test");
        ContractDefinition emptyTag = new ContractDefinition(rl("empty"), new ContractDefinition.TagTarget(
                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, rl("unused_tag"))), 1, 1,
                ContractDefinition.Payout.fixed(BigDecimal.ONE), 1, Optional.empty(), "test");
        ContractDefinition fine = definition("fine", new ContractDefinition.ItemTarget(Items.BARRIER), 1, 1, "1", 1);
        ContractDefinition off = definition("off", new ContractDefinition.ItemTarget(Items.BARRIER), 1, 1, "1", 0);
        List<String> warnings = variableeconomy.contracts.service.ContractDiagnostics.run(server, List.of(times, itemClass, emptyTag, fine, off));
        helper.assertTrue(warnings.stream().anyMatch(w -> w.contains(emptyTag.id().toString()) && w.contains("holds no items")),
                "an empty tag is warned about: " + warnings);
        if (variableeconomy.contracts.compat.MarketBridge.available()) {
            helper.assertValueEqual(warnings.size(), 1, "with the market, nothing else: " + warnings);
        } else {
            helper.assertValueEqual(warnings.size(), 3, "without the market, one line for each definition needing it: " + warnings);
            helper.assertTrue(warnings.stream().filter(w -> w.contains("the market add-on isn't installed")).count() == 2,
                    "saying why: " + warnings);
        }
        helper.succeed();
    }

    private static ContractDefinition definition(String name, ContractDefinition.Target target, int min, int max, String perUnit, int weight) {
        return new ContractDefinition(rl(name), target, min, max, ContractDefinition.Payout.fixed(new BigDecimal(perUnit)), weight,
                Optional.empty(), "test");
    }
}
