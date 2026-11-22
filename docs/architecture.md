# Architecture

A map of the code for contributors. For what the add-on does, read the [admin guide](admin-guide.md) and the [player guide](player-guide.md) first: the code follows them closely, and their terms (contract definition, board, claim, deposit, payout) are the ones used here. If you're writing a separate mod that uses this one, read the [add-on guide](api.md) instead.

Variable Economy is four mods, each in its own repository: [core](https://github.com/The-Azotus/variable-economy-core) (`variable_economy`), the [market](https://github.com/The-Azotus/variable-economy-market) (`variable_economy_market`), contracts (`variable_economy_contracts`, this repository) and the [exchange](https://github.com/The-Azotus/variable-economy-exchange) (`variable_economy_exchange`). Each has its own jar, mod id, `mods.toml`, config file, lang file, network channel and command root, and they talk to each other only through their API packages. Contracts requires core and uses the market when it's installed.

## Building and testing

Java 21 and NeoForge 21.1 for Minecraft 1.21.1. Gradle's toolchain finds Java 21, or downloads it; the Gradle wrapper is in the repository.

The build is one project. `build.gradle` names the mods contracts builds against and the one boundary exception; the rest is in the convention plugin `buildSrc/src/main/groovy/variable-economy.mod.gradle`, the same file in each Variable Economy repository (NeoForge, Parchment, the repositories, the optional mods, `mods.toml`, the GameTests and their log check, `checkModuleBoundaries`, the javadoc and the publication). `gradle.properties` says which mod this is (`module`, `mod_id`, `mod_name`, `mod_version`) and which core and market it builds against. `src/main/templates/META-INF/neoforge.mods.toml` is filled in from it: contracts requires core from `core_version` up to the next minor version, and takes the market, optionally, the same way from `market_version`.

**Core and the market.** Contracts builds against core, and compiles against the market's API (it never loads the market in its tests or runs). `gradle.properties` says which versions and where they come from:

- `core_source=curseforge` and `market_source=curseforge`, the defaults, use the released jars that the `core_curseforge_*` and `market_curseforge_*` properties name, through [CurseMaven](https://www.cursemaven.com/): for example, core 0.6.1 is `curse.maven:variable-economy-1818087:9710072` and the market 0.6.1 `curse.maven:variable-economy-market-1818103:9717524`. This mod requires core 0.6.2 or later, so until that core's file is set here, build with `-Pcore_source=local`.
- `core_source=local` and `market_source=local` use `variableeconomy:core:<core_version>` and `variableeconomy:market:<market_version>` from your local Maven instead, for building against a core or market that isn't released. Clone [variable-economy-core](https://github.com/The-Azotus/variable-economy-core) and [variable-economy-market](https://github.com/The-Azotus/variable-economy-market) and run `gradlew publishToMavenLocal` in each, core first.
- `-Pcore_source=local` and `-Pmarket_source=local` set them for one build.

**Tasks:**

- `gradlew build` compiles, runs `checkModuleBoundaries` (see [Modules and their boundaries](#modules-and-their-boundaries)), and builds the jar, the sources jar and the javadoc jar in `build/libs/`. The javadoc covers only `variableeconomy.contracts.api`, with doclint on, so a broken link or bad HTML in its javadoc fails the build.
- `gradlew runGameTestServer` runs contracts' GameTests (24 today) with core loaded and without the market, then `checkGameTestLog`, which fails if the run loaded other mods of ours than core and contracts, or logged an error, or a warning from our own code, before its tests began (its report is `build/reports/gameTestLog.txt`). The run loads the optional mods core works with (FTB Teams, ProgressiveStages and KubeJS); `-PwithoutMods=all`, or a comma-separated list of `ftbteams`, `progressivestages` and `kubejs`, leaves them out, with a test world of its own (`run-gametest-without-<mods>/`).
- `gradlew runClient` and `gradlew runServer` start a dev client or server with core and contracts.
- `gradlew publishToMavenLocal` publishes `variableeconomy:contracts:<mod_version>` with its sources and javadoc jars; that's how another mod builds against contracts. The POM names no dependencies: an add-on declares core itself.

**The GameTests** live in their own source set, `src/gametest`, and never ship in the jar. Their world is in `run-gametest/`, and its `config/` starts from `src/gametest/config/`: a ProgressiveStages stage locking the dragon egg, which test players never have, so that mod doesn't write its own example stages and the tests can check a real lock. Run them with `gradlew runGameTestServer`.

The test world persists between runs, so tests undo what they change (definitions, config values) and compare money relatively. Tests that need their own state use their own `ContractBoard` rather than the world's.

Contracts' own tests run without the market. Tests of contracts with the market installed aren't in this repository.

**Writing a test.** Put it in the `variableeconomy.contracts.gametest` package. Each class is marked `@GameTestHolder(ContractsModule.MOD_ID)` and `@PrefixGameTestTemplate(false)` and imports `CoreTestSupport.*` and `ContractTestSupport.*`; each test is a static `@GameTest(template = EMPTY)` method that ends with `helper.succeed()`. The `empty` structure is shipped under contracts' own namespace, since NeoForge looks a test's template up in its holder's namespace. From the support classes:

- `new CoreFixture(helper)` gives a fresh player with an empty inventory and an empty bank account.
- `board(helper, …)` makes a board of the test's own; `withSetting(setting, value, body)` changes a config value while `body` runs, then puts it back; `ContractDefinitions.INSTANCE.replaceForTesting` swaps the definitions (put them back in a `finally`).
- `contractEventsDuring(body)` records contracts' events while `body` runs.
- `assertScreenFollowsTheBlock`, `assertRecipeAndFacing`, `assertSolidAndDropsItself` and `assertOwnNamespace` check the block and the lang file.
- A test that writes files makes its own folder with `Files.createTempDirectory` and removes it with `deleteTree` in a `finally`.

`CoreTestSupport` is contracts' own copy of what every module's tests share, since core's jar holds no test code.

## Packages

All code is under `variableeconomy.contracts`.

### The module's shape

Every Variable Economy mod has the same shape; contracts':

- `ContractsModule`: the mod's `@Mod` class, with `MOD_ID` and `LOGGER`. Its constructor registers the config, then the block, menu, packets, the definitions' reload listener, commands, server events and tick work. `client.ContractsClient` is its client-only `@Mod` class: the screen.
- `ContractsConfig`: the server config file, `variable_economy_contracts-server.toml`. Its `install` fills `ContractSettings`, which the rest of the module reads. `ContractSettings` also decides whether a player may use the commands under its `access_mode`.
- One block and one screen: `block` (the block and its registration), `menu` (the menu the screen runs in), `network` (`ContractPayloads` with every packet, `ContractNetwork` registering them, `ContractSync` with what each player is sent and when, and the request handlers) and `client` (the screen, the client's copy of the board, and its own copy of small helpers such as `PriceFormat`). No screen code is shared with other modules.
- `service` for the rules, `data` for what's saved, `command` for its commands, and its own event poster (`ContractsEvents`) and result enum (`ContractResult`).

### Contracts' packages

| Package | What's in it |
| --- | --- |
| `api.event` | `ContractEvent`. See [The add-on API](#the-add-on-api). |
| `definition` | `ContractDefinition` and `ContractDefinitions`, the reload listener that reads contract definitions from datapacks (`data/<namespace>/contract_definitions/`) and the config folder (`config/variable_economy_contracts/definitions/`), checks `neoforge:conditions`, and keeps them for the board. |
| `service` | `ContractService` (boards from the definitions, claims, deliveries, deadlines, payouts and refunds; registers a net-worth contributor for held deposits), `ContractPricing` (pure arithmetic: rewards, the cap, deposits and refunds), `ContractDiagnostics` (the definitions' warnings at start and reload), `ContractResult`, `ContractsEvents`. |
| `data` | `ContractBoard`: the contracts on offer and held, the day count, notices and money counts, saved with the world (`data/variable_economy_contracts.dat`). |
| `compat` | `MarketBridge`, the only class that reads the market. |
| `block`, `menu`, `network`, `client` | The Contract Board (`ContractBoardBlock`, `ContractBlocks`), `ContractBoardMenu`, `ContractPayloads`/`ContractNetwork`/`ContractSync`, and `ContractBoardScreen` with `ContractsView` and `ClientContractState`. |
| `command` | `/contracts` (opens the screen), its player commands and `/contracts admin` (`ContractCommands`). |

The built-in definitions are data in the jar, `data/variable_economy_contracts/contract_definitions/`: `market/`, one per market item class, loaded only with the market, and `vanilla/`, fixed payouts, loaded only without it, each set chosen by `neoforge:conditions`.

Contracts' ids are all in its own namespace: the block, item, menu, packets, recipe, loot table, money reasons (`variable_economy_contracts:contract_deposit`, `contract_payout`) and lang keys (`screen.variable_economy_contracts.…`). A refused action returns a `ContractResult`, and each value needs a line `screen.variable_economy_contracts.result.<value in lower case>` in the lang file, or the screen shows the raw key.

Every package except `api.event` is marked `@ApiStatus.Internal` in its `package-info.java`: add-ons mustn't use it, and it may change in any release. A new package gets a `package-info.java` with the same annotation.

## Modules and their boundaries

Contracts is a separate mod that may be installed without the market, and talks to core and the market only through their API packages:

- Contracts' main code uses only its own package and `variableeconomy.core.api` (with `.event`).
- Its `api.event` package uses only itself and `variableeconomy.core.api`.
- The one exception: `compat/MarketBridge` may also use `variableeconomy.market.api`. It checks whether the market is loaded (`ModList`, for `variable_economy_market`) and touches it only from a nested `Present` class, so contracts loads without the market. Without it, definitions paying a multiplier or asking for a market class are off, and fixed-payout definitions work normally.
- The GameTests may reach into core's internals and contracts' own, never the market.

Gradle's dependencies enforce most of this: contracts compiles against core and the market (`compileOnly`, the market without its own dependencies); core is also on the dev runtime and the GameTests' classpath, and the market never is. The task `checkModuleBoundaries` (in the convention plugin) checks the rest: it scans every source file for references to `variableeconomy.…` and fails the build on any that isn't allowed. The `MarketBridge` exception is declared in `build.gradle` (`boundaryExceptions`). `gradlew check`, and so `gradlew build`, runs it.

When contracts needs something from core or the market that isn't in their API, it's added to that API (in that mod's repository, with javadoc and a changelog line) rather than reached for in their internals.

## Rules every change follows

These keep money safe, whatever combination of Variable Economy's mods is installed. `ContractService` repeats the ones it relies on at its top.

- **Server thread only.** Every claim, delivery, payout and bank call runs on the server main thread; the bank isn't thread-safe. The packet handlers in `ContractNetwork` run on the main thread (the registrar's default), and `ContractService` checks (`requireServerThread`), throwing `IllegalStateException` from another thread.
- **Check everything, then change.** An action makes every check before its first change, and after the first change no step may fail.
  - Claim (`ContractService.claim`): check the contract is still on the board, the item gate and the definition's stage, that it's open, and the player's claim limit; then charge the deposit through `Money` (stop if it fails: the charge is itself the funds check), then lock the contract to the player.
  - Delivery (`deliver`): check the player holds it, the quantity (the per-request limit, what's still wanted, whole deliveries), count plain items with `PlayerItems.count`, and on the last unit check the account can take the payout (`payee`); then `PlayerItems.remove` exactly `qty`, update the board, and pay.
  - Abandon (`abandon`): check the player holds it and the account can take what's paid back; then update the board and pay.
  - A new check goes before the first change: above the `Nothing has changed yet` comment in `deliver`, and before the charge in `claim`.
- **Money only through `Money`.** Every deposit, payout and refund goes through core's `Money` API (`CoreApi.money()`): `charge` destroys money and is all-or-nothing, `pay` creates it. Each call names a `Reason` in contracts' namespace. Payments are never coins handed out. Payouts go to the account the deposit came from while it exists, otherwise the holder's personal account; a payment the bank can't take at the moment (at expiry, or when contracts are turned off) is owed by core and paid later.
- **No profit from a round trip.** Buying N from the market and delivering them must never pay. With the market installed, `ContractService.cap` and `ContractPricing` cap every payout, fixed or multiplier, one bit below the market's `lowestBuyCost`, which contracts reads through `MarketBridge`, and items the market doesn't buy (`can_sell: false`) are never offered. The payout is worked out again when it's made, never above what was offered. The one exception is the admin's choice: with `contracts.clamp_to_market` off, payouts aren't capped, and `ContractDiagnostics` warns at every start and reload about each definition players could then profit from.
- **Totals in `long`.** Rewards, deposits and payouts are `long`, worked out with exact arithmetic. A contract whose reward and deposit together wouldn't fit is never offered (`ContractPricing.offer`); a delivery larger than `max_quantity_per_request` is refused as `TOO_LARGE`; a payout an account couldn't take without overflowing is refused before anything changes (`payee`).
- **Main inventory only, and never drop items.** Contracts takes items only, through `core.api.PlayerItems`: only the hotbar and the 27 storage slots count, and only plain copies (`CoreApi.isPlain`, which honours core's `ignored_components`). It never gives items, so nothing can land on the ground.
- **Respect the item gate.** No player claims a contract for an item they haven't unlocked: `claim` asks `CoreApi.itemGate().isLocked`, and checks the definition's `stage` with `ItemGate.hasStage`. The board shows such contracts greyed out with the reason. A contract already held stays deliverable.
- **Coins are never contract items.** Paying for one would create money from nothing. `ContractDefinitions` skips a definition asking for a coin, and `ContractService` leaves coins out of every tag and class pool, both through `CoreApi.isCoin`.
- **The market stays behind its bridge.** Only `compat.MarketBridge` uses the market, from its nested `Present` class. A missing market means its features are simply absent, with one warning per definition that needs it and no other errors or log spam.
- **Events after the change.** An event is posted only once its whole change is complete, through `ContractsEvents`. See [The add-on API](#the-add-on-api).

## How a delivery flows

1. The player presses Deliver in the board's screen, which sends a request in a `ContractPayloads` packet.
2. `ContractNetwork`'s handler runs on the server thread and calls `ContractSync`, which checks the player still has the board open and calls `ContractService.deliver`.
3. `ContractService` checks the contract, the holder, the quantity and the items, and on the last unit the account; then removes the items with `PlayerItems`, updates the `ContractBoard`, pays through `Money` on the last unit, and posts `ContractEvent.Delivered` (and `Completed`).
4. `ContractSync` sends the result to the player and the new board to everyone with it open.
5. At the end of the tick, core posts one `BalanceChangedEvent` for the account; `ContractSync` listens for it, so an open board shows the new balance.

`ContractService` builds boards from the contract definitions, picking each pool's items (an item, a tag's items, or the market items `MarketBridge` reports in a class), and prices them from what `MarketBridge` reports (base price, normal sell total and lowest buy cost); `ContractPricing` works out the reward, payout, deposit and refunds from those numbers. The server tick (`ContractService.tick`) replaces the board when its day comes and expires held contracts past their deadline.

## The add-on API

Contracts' API is the events in `variableeconomy.contracts.api.event`; [api.md](api.md) is their guide for add-on authors. They're `@ApiStatus.Experimental` until 1.0.

**Events.** `ContractsEvents` puts them on `NeoForge.EVENT_BUS`, logging and ignoring a listener that throws. They're posted where the change happens, so actions from the screen and the commands post them alike:

| Event | Posted in |
| --- | --- |
| `ContractEvent.Claimed`, `Delivered`, `Completed`, `Abandoned` | `ContractService.claim`, `deliver` and `abandon`, after the money moved |
| `ContractEvent.Expired` | `ContractService.settle` collects them; `expireOverdue` and `settleAll` post them once the contracts are off the board |

The rule: **an event is posted only after its whole change is complete**, as the last step. A listener may call back into Variable Economy, so it must never see half a transaction. When one action makes several changes (several held contracts running out at once, or contracts being turned off), collect the events and post them with `ContractsEvents.postAll` after the last change. A new event follows the same pattern, is never cancellable, and is posted only for actions that succeeded.

**Changing the API.** For any change to a public type or method in `api.event`, or to when an event fires:

1. Update the javadoc. `gradlew javadoc` must pass (doclint is on).
2. Update [api.md](api.md): the section that describes it, and a line in its changelog under the version that ships it.
3. Raise `mod_version`: a minor version (0.6 to 0.7) for a change that can break add-ons, at least a patch version otherwise. Releases are tagged `v<version>`.

## Design notes

- **One board, shared.** Every player sees the same board, replaced every `board.refresh_days` in-game days. The day count only moves forward (`ContractBoard.observe`), so setting the time back can't replace a board early or keep one past its day.
- **Held contracts outlive the board.** A claimed contract has its own deadline, `contract.duration_days` from claiming, and stays when the board is replaced. `Expired` fires for a held contract's own deadline, not for a new board.
- **Deposits are held money.** A deposit is charged when claiming and counts, through a net-worth contributor, toward the account it came from, which also stops that account being deleted. Turning contracts off returns deposits whole.
- **Definitions, not catalog fields.** Contracts come from definition files, one per definition, replaced whole by ID. Before 0.6.0 they came from `contract_*` fields in the market's catalog; those are gone.
- **The market is optional.** With it, the built-in definitions follow its item classes and every payout is capped by its prices. Without it, seventeen fixed-payout vanilla definitions give core and contracts alone a board.
- **Only offered when it beats the market.** An item is offered only if its contract pays more than selling the same units to the market at base price, so a contract is always the better deal for items a player gathers, and never a way to turn market purchases into profit.
