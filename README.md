# Variable Economy: Contracts

A shared board of delivery contracts for Minecraft servers, for NeoForge 1.21.1. Players claim a contract, deliver the items and get paid; packs and admins decide what the board offers. All money moves through each player's bank account, counted in **bits**.

This repository is **contracts**, an optional add-on for [Variable Economy](https://github.com/The-Azotus/variable-economy-core). It adds:

- **One board for the whole server.** Everyone sees the same contracts: orders for a number of one item, such as *256 × Oak Log*, each with a reward. A new board replaces it every few in-game days (3 by default).
- **Claims and deposits.** Claiming a contract locks it to you and takes a deposit. Deliver the items, in batches if you like, before its time runs out; the last unit pays the reward and returns the deposit. Abandon it, or run out of time, and you're paid for what you delivered and get part of the deposit back.
- **Contract definitions.** What the board offers comes from JSON files in any datapack or the config folder: an item, an item tag or a market item class, a quantity range and a payout per unit. Built-in definitions give every server a board from the start.
- **With the market, or without it.** With the [market add-on](https://github.com/The-Azotus/variable-economy-market), definitions can pay a multiple of an item's market price or ask for a market item class, and every payout is capped just below what buying the items from the market could cost, so contracts can't be farmed from the market. Without it, definitions pay fixed amounts.
- **Locked contracts.** With ProgressiveStages, a contract for an item a player hasn't unlocked, or needing a stage they lack, shows greyed out with the reason.
- **The Contract Board.** A craftable block opens the board. Players can also use `/contracts` if the server allows it.
- **Hooks for packs and mods.** Definitions as data, commands for functions, and Java events for every claim, delivery and ending.

## The four mods

Variable Economy is four mods. **Core** is required. The **market**, **contracts** and **exchange** are optional add-ons, and any combination of them works on top of core.

| Mod | Mod id | Adds | Repository |
| --- | --- | --- | --- |
| Core | `variable_economy` | The coins and bank, the Bank block and Bank Card, accounts, `/pay`, account history, the leaderboard, money-supply stats, `/bank` | [variable-economy-core](https://github.com/The-Azotus/variable-economy-core) |
| Market | `variable_economy_market` | A server-owned market whose prices move with supply and demand: the Market Terminal, prices and price tooltips, `/market` | [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) |
| Contracts | `variable_economy_contracts` | Delivery contracts with deposits: the Contract Board, `/contracts`. Without the market, only fixed-payout contracts. | This one |
| Exchange | `variable_economy_exchange` | Player buy and sell orders: the Exchange block, `/exchange` | [variable-economy-exchange](https://github.com/The-Azotus/variable-economy-exchange) |

Each mod has its own block, screen, config file and commands. If a mod isn't installed, its features are simply absent.

## Requirements and installation

- [Variable Economy core](https://github.com/The-Azotus/variable-economy-core) **0.6.3** or a later 0.6.x (up to, but not including, 0.7).
- Minecraft 1.21.1 with NeoForge 21.1.228 or newer, and Java 21.
- Optional: [Variable Economy: Market](https://github.com/The-Azotus/variable-economy-market) 0.6.1 or a later 0.6.x, for contracts that pay on market value or ask for a market item class, and for payouts capped by market prices.
- Optional, through core: [ProgressiveStages](https://modrinth.com/mod/progressivestages) 3.x, for locked contracts. FTB Teams parties and named accounts can claim through a shared account.

Install the mods you use on the **server and every client**, all of the same version. Clients need them for the screen. Put `variable_economy_contracts-<version>.jar` in the mods folder; the `-sources` and `-javadoc` jars are for developers.

What changed in each version is in the [changelog](CHANGELOG.md). Worlds from Dynamic-Economy, the mod's name before 0.6.0, can't be carried over.

## Using it

### The Contract Board

Craft a **Contract Board** from 8 Bits around a book, and right-click it. Or type `/contracts`, if the server allows it. A screen opened from the block closes if you walk more than about 8 blocks away.

```
Bit  Bit   Bit
Bit  Book  Bit
Bit  Bit   Bit
```

The board lists every contract: the item and amount, the reward, and whether it's open, locked, held by someone (with your progress and days left, if it's yours), done or abandoned. The heading says how long the board has left and how many contracts you hold. Click a contract to see its reward, the reward per unit, the deposit, progress and how many you have.

### Claiming and delivering

1. **Claim** a contract. This takes a **deposit** (a fifth of the reward by default) from your active bank account. Nobody else can claim it now, and you have a few in-game days to deliver (3 by default). It stays yours when a new board goes up. You may hold 2 at once by default.
2. **Deliver** the items from your main inventory: set the quantity and click **Deliver**. **All** fills in what you hold, up to what's still wanted. Some servers want every unit at once. Delivered items are used up.
3. The **last unit pays the reward** and returns the deposit.

**Abandon** (click twice) pays for what you've delivered and returns part of the deposit (half by default). If its time runs out first, you're paid for what you delivered and, by default, the deposit is lost. Payments go back to the account the deposit came from, online or not.

Only plain items count (not enchanted, damaged or renamed), from the hotbar and the 27 storage slots.

**Why contracts pay well.** On servers with the market, a contract normally pays less per unit than the market could ever sell you that item for, so buying from the market to fill one loses money. It pays more than selling the same items to the market at their normal price, so if you gather or farm the items yourself, a contract is the better deal.

## Commands

Player commands follow the contracts `access_mode`: with `BLOCK_ONLY`, only operators can use them, and `/contracts` tells players to find a Contract Board.

| Command | What it does |
| --- | --- |
| `/contracts` | Opens the Contract Board's screen. |
| `/contracts list` | The board in chat, with each contract's ID. |
| `/contracts claim <id>` | Claims a contract. |
| `/contracts deliver <id> <qty>` | Delivers items to a contract you hold. |
| `/contracts abandon <id>` | Gives up a contract you hold. |

Admin commands need permission level 2:

| Command | What it does |
| --- | --- |
| `/contracts admin` | The whole board with holders, progress and deadlines, and the money contracts have paid and kept. |
| `/contracts admin refresh` | Replaces the board's open and finished contracts now; held ones stay. Logged in core's audit log. |
| `/contracts admin definitions [page]` | Every contract definition with its terms, where it came from and how many of its items can be offered now, then any skipped files and warnings. |
| `/contracts admin item <item>` | Each definition that asks for the item, with what it pays per unit, or why it can't offer one. |

## Configuration

The settings are in `config/variable_economy_contracts-server.toml`, created on first start. Changes apply when the file is saved.

| Setting | Default | What it does |
| --- | --- | --- |
| `access_mode` | `COMMAND_AND_BLOCK` | `BLOCK_ONLY` keeps players to the Contract Board. |
| `max_quantity_per_request` | `640` | The most one delivery can hand in, except the one delivery that finishes a contract when batches are off. |
| `[contracts]` `enabled` | `true` | Offer contracts. Turning it off settles held contracts and returns their deposits whole. |
| `[contracts]` `clamp_to_market` | `true` | With the market, cap every payout below what buying the items could cost, and never offer items the market doesn't buy. |
| `[board]` `size`, `refresh_days` | `8`, `3` | Open contracts on each board, and in-game days between boards. |
| `[contract]` `duration_days` | `3` | In-game days to deliver after claiming. |
| `[claims]` `max_per_player` | `2` | Contracts one player may hold at once. |
| `[deposit]` `mode`, `amount` | `PERCENT`, `20` | The deposit: a percentage of the reward (rounded up), or `FLAT` bits. |
| `[deposit]` `refund_on_abandon`, `refund_on_expiry` | `50`, `0` | Percent of the deposit returned on abandoning, and when time runs out. |
| `[delivery]` `partial_allowed` | `true` | Deliveries in batches. Off: every unit still wanted at once. |

Core's `ignored_components` setting also applies to deliveries. Every setting, with its range, is in the [admin guide](docs/admin-guide.md#server-config).

## Contract definitions

Each definition is one JSON file: in any datapack at `data/<namespace>/contract_definitions/<path>.json`, or in `config/variable_economy_contracts/definitions/<namespace>/<path>.json`, which applies to every world. A file with the same ID in a higher pack, or in the config folder, replaces the whole definition. Definitions load at start and on `/reload`.

```json
{
  "tag": "minecraft:logs",
  "quantity": { "min": 64, "max": 256 },
  "payout": { "per_unit": 10 },
  "weight": 3
}
```

| Field | Meaning |
| --- | --- |
| `item`, `tag` or `market_class` | Exactly one. A tag or class is a pool: each contract is for one item of it. Coins are never contract items. |
| `quantity` | Units one contract asks for: a number, or `{ "min", "max" }`, 1 to 100,000. Sizes of 64 or more round down to multiples of 16. |
| `payout` | `{ "per_unit": 2.5 }` bits per unit, or `{ "multiplier": 1.25 }` times the item's market base price (needs the market). |
| `weight` | How often it's picked, relative to the others (default 1; 0 switches it off). |
| `stage` | A ProgressiveStages stage a player needs to claim it. |
| `description` | Notes for admins; ignored. |

NeoForge's `neoforge:conditions` work in every file, for example to load a definition only when a mod is installed.

### The built-in definitions

With the market, one per market item class (`variable_economy_contracts:market/<class>`), each paying 1.25 × the item's base price:

| Class | Units | Weight |
| --- | --- | --- |
| `common_resource` | 64–512 | 40 |
| `farmable` | 64–512 | 30 |
| `common_processed` | 32–256 | 20 |
| `rare_resource`, `rare_processed` | 4–16 | 5 each |
| `rare_loot` | 2–8 | 3 |

Without the market, 17 fixed-payout vanilla definitions (`variable_economy_contracts:vanilla/<name>`) instead:

| Items | Units | Bits per unit |
| --- | --- | --- |
| Logs (any) | 64–512 | 10 |
| Wool (any) | 64–512 | 5 |
| Bones, carrots, potatoes, wheat, sugar cane | 64–512 | 2.5 |
| Cobblestone, gravel, sand, string | 64–512 | 1.25 |
| Leather | 64–512 | 8.75 |
| Pumpkins | 64–512 | 7.5 |
| Coal, copper ingots | 32–256 | 10 |
| Iron ingots | 32–256 | 40 |
| Redstone | 32–256 | 12.5 |

To change or switch off a built-in definition, put a whole definition with the same ID in the config folder, with `"weight": 0` to switch it off. `/contracts admin definitions` shows what loaded and what each can offer.

## For pack makers

- **Definitions** can go in any datapack, including KubeJS's and OpenLoader's. Use your own namespace, so you add to the built-in ones rather than replace them.
- **Commands from functions.** Functions and command blocks run at level 2. The player commands need a player as the source:

  ```mcfunction
  execute as @p run contracts deliver 3 64
  contracts admin refresh
  ```

- Balances, payments and charges are core's commands and KubeJS bindings: see [core's README](https://github.com/The-Azotus/variable-economy-core#for-pack-makers).

The [add-on guide](docs/api.md#contract-definitions-as-data) has the details for shipping definitions in a mod.

## For mod authors

Contracts' Java API is its events, in `variableeconomy.contracts.api.event`. Money, accounts and item locks are [core's API](https://github.com/The-Azotus/variable-economy-core#for-mod-authors). It is marked experimental until 1.0, and every change is listed in the [add-on guide's changelog](docs/api.md#changelog).

### Depending on contracts

The released jars are on CurseForge (contracts is project 1818118, core 1818087) and can be used through [CurseMaven](https://www.cursemaven.com/):

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { url = 'https://cursemaven.com' } }
        filter { includeGroup 'curse.maven' }
    }
}

dependencies {
    compileOnly 'curse.maven:variable-economy-1818087:<core file id>'
    compileOnly 'curse.maven:variable-economy-contracts-1818118:<contracts file id>'
}
```

Then declare it in your `neoforge.mods.toml`, usually as optional:

```toml
[[dependencies.yourmod]]
    modId="variable_economy_contracts"
    type="optional"
    versionRange="[0.6,)"
    ordering="AFTER"
    side="BOTH"
```

As an optional dependency, check `ModList.get().isLoaded("variable_economy_contracts")` before touching contracts, and register your listeners from a class loaded only after that check. For sources and javadoc, clone this repository and run `gradlew publishToMavenLocal`; it publishes `variableeconomy:contracts:<version>`. The [add-on guide](docs/api.md#depending-on-contracts) gives the file IDs and the full setup.

### Events

All are posted on `NeoForge.EVENT_BUS` on the server thread, after the change is complete, and are never cancellable. Listen for `ContractEvent` itself to hear all five.

| Event | Fires when | Adds |
| --- | --- | --- |
| `ContractEvent.Claimed` | A player claimed a contract and paid its deposit. | `getDeposit` |
| `ContractEvent.Delivered` | The holder delivered units, the last delivery included. | `getQuantity` (this delivery) |
| `ContractEvent.Completed` | The last unit arrived: reward paid, deposit returned. After its `Delivered`. | `getReward` (without the deposit), `getDeposit` |
| `ContractEvent.Abandoned` | The holder gave it up. | `getPaid`, `getDepositReturned`, `getDepositLost` |
| `ContractEvent.Expired` | A held contract ran out of time, or contracts were turned off. | `getPaid`, `getDepositReturned`, `getDepositLost` |

Every event also has `getServer`, `getContractId`, `getDefinition`, `getHolder` (a UUID; the player may be offline), `getItem` (a `ResourceLocation`), `getUnits` and `getDelivered`. Amounts are `long` bits.

```java
/** Registered only when contracts is loaded. */
static void listen() {
    NeoForge.EVENT_BUS.addListener((ContractEvent.Completed event) -> {
        // count a finished contract towards a quest for event.getHolder()
        long earned = event.getReward();
    });
}
```

The [add-on guide](docs/api.md) covers each event in full.

## Guides

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |
| A mod or modpack author | [Add-on guide](docs/api.md) |
| Reading the code | [Architecture](docs/architecture.md) |

## Building from source

Run `gradlew build` with Java 21. Gradle's toolchain finds an installed JDK or downloads one, and the Gradle wrapper is in the repository. The build compiles the mod, checks the module boundary (see [docs/architecture.md](docs/architecture.md)), and puts the jar, a sources jar and a javadoc jar in `build/libs/`.

Contracts builds against core, and compiles against the market's API (it never loads the market in its own tests or runs). `gradle.properties` says which versions and where they come from:

- `core_source=curseforge` and `market_source=curseforge` (the defaults) use the released jars that the `core_curseforge_*` and `market_curseforge_*` properties name, through CurseMaven.
- `core_source=local` and `market_source=local` use `variableeconomy:core:<core_version>` and `variableeconomy:market:<market_version>` from your local Maven instead: clone [variable-economy-core](https://github.com/The-Azotus/variable-economy-core) and [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) and run `gradlew publishToMavenLocal` in each, core first. `-Pcore_source=local` and `-Pmarket_source=local` set them for one build.

Then:

- `gradlew runGameTestServer` runs contracts' automated tests with core loaded and without the market, then checks the log. `-PwithoutMods=all`, or a comma-separated list of `ftbteams`, `progressivestages` and `kubejs`, leaves the optional mods out.
- `gradlew runClient` and `gradlew runServer` start a dev client or server with core and contracts.
- `gradlew publishToMavenLocal` publishes `variableeconomy:contracts:<version>` to your local Maven.

Released versions are tagged in git, for example `v0.6.1`.

## Licence

MIT; see [LICENSE](LICENSE).
