# Add-on guide

This guide is for mod developers writing an add-on that uses the contracts add-on of Variable Economy, and for pack makers who ship contract definitions as data. It assumes you know NeoForge modding, but nothing about this mod's code.

For what contracts do, read the [admin guide](admin-guide.md) first. This guide links to it rather than repeating it. Everything else in Variable Economy's API (accounts, money, item locks, net worth, the audit log, how to depend on the mods and keep a dependency optional) is in [core's add-on guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/api.md); market prices and trading are in the [market's](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/api.md).

## What you can do

There are two ways to integrate with contracts:

- **Without Java.** Ship contract definitions as data, so the Contract Board offers contracts for your items. This needs no build setup. See [Contract definitions as data](#contract-definitions-as-data).
- **With the Java API.** Listen for contracts being claimed, delivered, completed, abandoned and expired. See [Events](#events).

The Java API runs on the server only. A client-only mod can't use it.

Contracts' API is one package:

| Mod | Mod ID | Artifact | Package | What's in it |
| --- | --- | --- | --- | --- |
| Contracts | `variable_economy_contracts` | `contracts` | `variableeconomy.contracts.api.event` | Contract events. |

Contracts is an optional add-on on every server, so its package is there only when it's installed: check its mod ID before using it, even when your mod requires core (see [An optional dependency](#an-optional-dependency)).

### Stability

- The API is the package above. It's marked `@ApiStatus.Experimental` until 1.0: it may still change in a 0.x minor release (0.6 to 0.7, say). Every change is listed in this file's [changelog](#changelog).
- Everything else in the jar is internal. Those packages are marked `@ApiStatus.Internal` and may change in any release, a patch release included. Don't call them, even when a class is public: public there means "used by another package of this mod", not "supported".
- Data files and commands are described in the [admin guide](admin-guide.md), which is kept up to date with each release.

## Contract definitions as data

A mod or pack can ship contracts for the Contract Board as data: one JSON file per definition at `data/<namespace>/contract_definitions/<path>.json`, asking for an `item`, a `tag` or a market item class, with a quantity range and a payout per unit (fixed, or a multiple of the market price). The fields are in the admin guide's [Contract definitions](admin-guide.md#contract-definitions).

Put the file in **your own namespace**, such as `data/yourmod/contract_definitions/copper_gears.json`:

```json
{
  "item": "yourmod:copper_gear",
  "quantity": { "min": 16, "max": 128 },
  "payout": { "per_unit": 48 },
  "weight": 2,
  "neoforge:conditions": [ { "type": "neoforge:mod_loaded", "modid": "yourmod" } ]
}
```

- Use your own namespace, so you add definitions rather than replace Variable Economy's built-in ones (`variable_economy_contracts:market/…` and `:vanilla/…`).
- NeoForge's `neoforge:conditions` work in these files, so a definition for another mod's items can load only when that mod is installed. A `multiplier` payout or a `market_class` needs the market: load such a definition only with `variable_economy_market`, as the built-in ones do, or it's off with a warning when the market isn't installed.
- Coins are never contract items. A definition asking for one is skipped.
- With the market installed, every payout is capped below what buying the items could cost, unless an admin turns that off.
- Check your file in game: `/contracts admin definitions` shows how yours load and how many of its items can be offered, and `/contracts admin item <item>` shows what each definition for the item pays, or why it can't offer it.

### Where your files sit in the priority order

A definition's ID is its path (`<namespace>:<path>`), and only one file is used for each ID: the highest. Unlike the market's price files, a higher file replaces the whole definition, not single fields. From lowest to highest:

1. **Datapacks**: mods' own jar data (yours, and contracts' built-in definitions, which are in its jar), the world's datapacks, and packs from OpenLoader, KubeJS and the like. A file in a higher pack replaces one with the same ID in a lower pack.
2. **The config folder**, `config/variable_economy_contracts/definitions/<namespace>/<path>.json`, which sits above every datapack and applies to every world.

So pack makers and server admins can replace or switch off (`"weight": 0`) any definition, yours included, without touching your jar.

## Events

Contracts posts these on `NeoForge.EVENT_BUS` (the game bus), from the package `variableeconomy.contracts.api.event`. Amounts are in bits.

| Event | Fires | Fields (getters) |
| --- | --- | --- |
| `ContractEvent.Claimed` | A player claimed a contract and paid its deposit. | common fields, plus `getDeposit` |
| `ContractEvent.Delivered` | The holder delivered units, the last delivery included. | common fields, plus `getQuantity` (this delivery) |
| `ContractEvent.Completed` | The last unit arrived; reward paid and deposit returned. Posted after its `Delivered`. | common fields, plus `getReward` (without the deposit), `getDeposit` |
| `ContractEvent.Abandoned` | The holder gave it up: delivered units paid for, part of the deposit returned (`deposit.refund_on_abandon`). | common fields, plus `getPaid`, `getDepositReturned`, `getDepositLost` |
| `ContractEvent.Expired` | A held contract ended unfinished: its own time ran out (`contract.duration_days` after claiming), or contracts were turned off. Delivered units are paid for, and part of the deposit returned (`deposit.refund_on_expiry`; all of it when contracts were turned off). A new board doesn't end held contracts. | common fields, plus `getPaid`, `getDepositReturned`, `getDepositLost` |

The common contract fields are `getServer`, `getContractId` (as `/contracts list` shows it), `getDefinition` (the [contract definition](#contract-definitions-as-data) it came from), `getHolder` (UUID; may be offline), `getItem` (a `ResourceLocation`, since a contract can outlive the mod that adds its item), `getUnits` (asked for in all) and `getDelivered` (delivered in all, counting this action). Listen for `ContractEvent` itself to hear all five. Money paid to the holder goes to the account the deposit came from while it exists, otherwise their personal account.

Rules for all of them:

- **Posted after the change is complete.** Money and items have moved and the saved data is updated. When one action changes several things (several held contracts running out at once), its events are collected and posted together after the last change, never in the middle.
- **Never cancellable.** To stop a player claiming a contract for an item, lock the item with an item lock rule (see core's add-on guide), or decide in your own code what to do.
- **Only for changes that happened**, and always on the server thread, so a listener may call the API.
- **A listener that throws is logged and ignored**, so it can't undo or interrupt Variable Economy's work. Listeners after it on the same event may then not run.
- A listener that moves money through the API causes further events. Guard against looping.
- When a contract expires and the bank can't be reached, its payment is owed and paid later; the event is posted either way.
- The account's balance changes reach you as core's `BalanceChangedEvent`, at the end of the tick, after the contract's event.

To subscribe, add a listener from a class that's loaded only when contracts is (see [An optional dependency](#an-optional-dependency)):

```java
NeoForge.EVENT_BUS.addListener((ContractEvent.Completed event) -> { /* ... */ });
```

or, if your mod requires contracts, a subscriber class on the game bus:

```java
@EventBusSubscriber(modid = "yourmod")
public final class ContractListeners {
    @SubscribeEvent
    public static void onContractDone(ContractEvent.Completed event) {
        // ...
    }
}
```

## Depending on contracts

You only need to build against contracts to listen for its events; definitions need no dependency at all. Core's add-on guide covers the general setup: the Gradle configurations, `neoforge.mods.toml` and keeping a dependency optional. What's particular to contracts:

**Where the jars come from.** The mods are distributed on CurseForge, and you can depend on the released jars through [CurseMaven](https://www.cursemaven.com/), with the repository block from core's guide. Contracts is CurseForge project **1818118**; contracts 0.6.1 is `curse.maven:variable-economy-contracts-1818118:9717567`, and core 0.6.1 is `curse.maven:variable-economy-1818087:9710072`. Use those in place of the `variableeconomy:` coordinates below. CurseMaven serves only the mod jars, without sources or javadoc. For the sources and javadoc, build against them from source: clone this repository, check out the release you target (tags start with `v`, such as `v0.6.1`), and run `gradlew publishToMavenLocal`. That publishes `variableeconomy:contracts:<version>` (the version without the `v`), with a sources jar and a javadoc jar covering `variableeconomy.contracts.api.event`. Publish core the same way from [its repository](https://github.com/The-Azotus/variable-economy-core): contracts' POM names no dependencies, so declare core yourself.

**Gradle** (`build.gradle`), with core and contracts from your local Maven:

```groovy
repositories {
    exclusiveContent {
        forRepository { mavenLocal() }
        filter { includeGroup 'variableeconomy' }
    }
}

configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    compileOnly 'variableeconomy:core:0.6.3'
    compileOnly 'variableeconomy:contracts:0.6.2'
    localRuntime 'variableeconomy:core:0.6.3'
    localRuntime 'variableeconomy:contracts:0.6.2'
}
```

**`neoforge.mods.toml`**, with contracts optional:

```toml
[[dependencies.yourmod]]
    modId="variable_economy_contracts"
    type="optional"
    versionRange="[0.6,)"
    ordering="AFTER"
    side="BOTH"
```

- Variable Economy's mods must be installed on the server and on every client, so use `side="BOTH"`.
- Since the API may change in a 0.x minor release, you can cap the range at the next minor version, such as `[0.6,0.7)`, and raise it once you've checked the changelog.

### An optional dependency

With `type="optional"`, your mod must load without contracts. Contracts is optional on every server, so this applies even when your mod requires core:

1. Check `ModList.get().isLoaded("variable_economy_contracts")` before touching anything of contracts'.
2. Keep every reference to `ContractEvent` (event types, lambdas) in a class of its own that is loaded only after that check passes.
3. Register your listeners from that class with `NeoForge.EVENT_BUS.addListener`, not with `@EventBusSubscriber`: NeoForge loads a subscriber class whether or not the mod is there.

Core's add-on guide has a worked example set up this way.

## Changelog

The API changes that concern contracts. Core's and the market's are in their own add-on guides.

- **0.6.2 (2026-11-23):** requires core 0.6.3 or a later 0.6.x, and with the market, market 0.6.2 or later. Nothing in the API changed.

- **0.6.1 (2026-11-17):** contracts is its own repository and release; nothing in its API changed.

- **0.6.0 (2026-11-14):** breaks every add-on. The mod is renamed from Dynamic-Economy to Variable Economy, split into four mods, and its API is replaced (core's add-on guide has the whole list).
  - Contracts is a separate optional mod, `variable_economy_contracts`. Check its mod ID before using its package.
  - Its blocks, items, menus, packets and money reasons are in its own namespace, `variable_economy_contracts`.
  - It has its own config file and command root: contracts' admin commands moved to `/contracts admin`.
  - `ContractEvent` moves to `variableeconomy.contracts.api.event`.
  - Contracts are reworked, and their events with them:
    - `ContractEvent` gains `getDefinition()`. `Abandoned` and `Expired` report `getPaid()`, `getDepositReturned()` and `getDepositLost()`, since part of a deposit can come back; `Expired.isDepositReturned()` is gone. `Expired` now fires when a held contract's own time runs out (or contracts are turned off), not when its board is replaced.
    - The market's internal `MarketPrices.contractTerms` and `ContractTerms` are gone, with the market's `contract_*` catalog fields: contracts come from [contract definitions](#contract-definitions-as-data).
    - A contract's deposit is a net-worth contributor, so an account holding one can't be deleted.
- **0.5.0:** first version of the API, as Dynamic-Economy.
