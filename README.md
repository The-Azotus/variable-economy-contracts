# Variable Economy: Contracts

A shared board of delivery contracts for Minecraft, an add-on for [Variable Economy](https://github.com/The-Azotus/variable-economy-core). Players claim a contract, deliver the items and get paid. Packs and admins define what the board offers.

- **One board for the whole server.** Everyone sees the same contracts: orders for a number of one item, such as *256 × Oak Log*, each with a reward. A new board replaces it every few in-game days (3 by default).
- **Claims and deposits.** Claiming a contract locks it to you and takes a deposit; the last unit you deliver pays the reward and returns the deposit. Abandon it, or run out of time, and you're paid for what you delivered and get part of the deposit back.
- **Bank payments.** Deposits and payouts go through the player's bank account, never coins.
- **Contract definitions.** What the board offers comes from JSON files in datapacks or the config folder: an item, an item tag or a market item class, a quantity range and a payout per unit.
- **With the market, or without it.** With the [market add-on](https://github.com/The-Azotus/variable-economy-market) installed, definitions can pay a multiplier on an item's market price or ask for a market item class, and every payout is capped just below what buying the items from the market would cost, so contracts can't be farmed from the market. Without it, definitions pay fixed amounts only, and seventeen built-in vanilla definitions give the board something to offer.
- **Locked contracts.** With ProgressiveStages, a contract for an item a player hasn't unlocked, or needing a stage they lack, is shown to them greyed out with the reason.
- **The Contract Board.** A craftable block opens the board. Players can also use `/contracts` if the server allows it.

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |
| A mod or modpack author | [Add-on guide](docs/api.md) |

Variable Economy is four mods: **core** (the coins, bank and accounts) is required, and the **market**, **contracts** and the **exchange** are add-ons on top of it. Core's repository has the [overview of all four](https://github.com/The-Azotus/variable-economy-core).

## Requirements

- [Variable Economy](https://github.com/The-Azotus/variable-economy-core) (core) 0.6.1 or a later 0.6.x (from 0.6.1 up to, but not including, 0.7)
- Minecraft 1.21.1 with NeoForge 21.1.228 or newer
- Java 21
- Optional:
  - [Variable Economy: Market](https://github.com/The-Azotus/variable-economy-market) 0.6.1 or a later 0.6.x: definitions paying on market value or asking for a market item class, and payouts capped by market prices.
  - ProgressiveStages 3.x (through core): contracts for items a player hasn't unlocked, or needing a stage they lack, are greyed out for them.

Install the mods you use on the **server and every client**, all of the same version. Clients need them for the screens.

What changed in each version is in the [changelog](CHANGELOG.md).

## Quick start

**Players:** right-click a **Contract Board**, or type `/contracts` if your server allows it. Click a contract, **Claim** it (this takes a deposit from your bank account), then **Deliver** the items from your inventory before its time runs out. The last unit pays the reward and returns the deposit. See the [player guide](docs/player-guide.md).

**Admins:**

1. Install core and contracts (and the market, if you want contracts that pay on market value), then start the server. The built-in definitions are on by default: with the market, one per item class; without it, seventeen fixed-payout vanilla ones.
2. Add your own definitions: one JSON file each in `config/variable_economy_contracts/definitions/<namespace>/<path>.json`, which applies to every world, or in a datapack at `data/<namespace>/contract_definitions/<path>.json`. For example, `{ "tag": "minecraft:logs", "quantity": { "min": 64, "max": 256 }, "payout": { "per_unit": 10 } }`. Run `/reload` to load them.
3. Check them with `/contracts admin definitions`, and see the board with `/contracts admin`.
4. Tune the board, claims and deposits in `config/variable_economy_contracts-server.toml`. To make the Contract Board the only way in, set `access_mode = "BLOCK_ONLY"` there.

The [admin guide](docs/admin-guide.md) covers every definition field, setting and command.

## For add-on authors

Other mods can listen for contracts being claimed, delivered, completed, abandoned and expired through Java events, and ship contract definitions as data without any Java. The [add-on guide](docs/api.md) covers both; core's [add-on guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/api.md) covers money, accounts and the rest of the API.

## Building from source

You need Java 21 (Gradle's toolchain finds it, or downloads it); the Gradle wrapper is in the repository.

Contracts builds against core, and compiles against the market's API (it never loads the market in its tests or runs). `gradle.properties` says which versions it uses and where they come from: with `core_source=local` and `market_source=local`, the defaults until the mods are on CurseForge, it uses `variableeconomy:core:<core_version>` and `variableeconomy:market:<market_version>` from your local Maven. So first clone [variable-economy-core](https://github.com/The-Azotus/variable-economy-core) and [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) and run `gradlew publishToMavenLocal` in each, core first. Once the CurseForge project and file IDs are filled in, `curseforge` will use the released jars through CurseMaven instead. `-Pcore_source=local` and `-Pmarket_source=local` override what `gradle.properties` says.

- `gradlew build` compiles, checks the module boundary, and builds the jar, a sources jar and a javadoc jar (in `build/libs/`).
- `gradlew runGameTestServer` runs contracts' automated tests with core loaded and without the market, then checks the log. `-PwithoutMods=all` (or a comma-separated list of `ftbteams`, `progressivestages` and `kubejs`) runs them without the optional mods.
- `gradlew runClient` and `gradlew runServer` start a dev client or server with core and contracts.
- `gradlew publishToMavenLocal` publishes `variableeconomy:contracts:<version>`.

Released versions are tagged in git (for example `v0.6.1`). To find your way around the code, start with [docs/architecture.md](docs/architecture.md).

## Licence

MIT; see [LICENSE](LICENSE).
