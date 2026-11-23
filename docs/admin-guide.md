# Admin guide

This guide is for server admins running the contracts add-on for Variable Economy. It covers:

- installing contracts, with or without the market
- how players reach the Contract Board
- contract definitions: what the board offers, and how to change it
- the board, claims, deposits and payouts, and why rewards can't be gamed
- where contract money comes from and goes
- every config option and command

For what players see, read the [player guide](player-guide.md). For mods and packs that ship contract definitions or listen for contracts, read the [add-on guide](api.md). The bank, accounts, coins, the leaderboard, the audit log and ProgressiveStages are core's; they're in [core's admin guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/admin-guide.md). Item classes, prices and the market's catalog fields are in the [market's admin guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/admin-guide.md).

## Installing

| Mod | Jar | Mod id | Adds | Needs |
| --- | --- | --- | --- | --- |
| Contracts | `variable_economy_contracts-<version>.jar` | `variable_economy_contracts` | The Contract Board, `/contracts` | Core; with the market, contracts paying on market value |

Contracts needs [Variable Economy](https://github.com/The-Azotus/variable-economy-core) (core) 0.6.3 or a later 0.6.x, so update them together. The [market add-on](https://github.com/The-Azotus/variable-economy-market) is optional: 0.6.1 or a later 0.6.x. Install each one you use on the server and on every client.

Contracts has its own config file in `config/` and its own commands. Without the market, the Contract Board offers fixed-payout contracts only ([Contract definitions](#contract-definitions)).

## Letting players in

The **Contract Board** block opens the board's screen, and `/contracts` opens the same screen from anywhere. A screen opened from the block closes if the player walks more than about 8 blocks away or the block is broken.

Choose whether players may use the commands with `access_mode` in `config/variable_economy_contracts-server.toml`. Core and each add-on have their own, so you can, say, keep `/bank` and make contracts block-only.

| `access_mode` | Players | Operators and console |
| --- | --- | --- |
| `COMMAND_AND_BLOCK` (default) | The Contract Board and every player command | Everything |
| `BLOCK_ONLY` | The Contract Board only. The bare `/contracts` tells them to find a Contract Board; `/contracts list`, `claim`, `deliver` and `abandon` are hidden. | Everything |

"Operators" here means permission level 2. The setting takes effect as soon as you save the file, with no restart. A player who opened the board by command is closed out if you switch to `BLOCK_ONLY` while they have it open.

### The Contract Board block

The block is 8 Bits around a book:

```
Bit  Bit   Bit
Bit  Book  Bit
Bit  Bit   Bit
```

| Block | ID | Recipe |
| --- | --- | --- |
| Contract Board | `variable_economy_contracts:contract_board` | `variable_economy_contracts:contract_board_from_bits` |

To change or remove the recipe, override it in a datapack: the recipe `variable_economy_contracts:contract_board_from_bits` is the file `data/variable_economy_contracts/recipe/contract_board_from_bits.json`. To hand the block out yourself, use `/give @p variable_economy_contracts:contract_board`. It faces whoever placed it, breaks by hand (faster with an axe) and always drops itself.

## Contracts

The **Contract Board** block (or `/contracts`) shows a board of delivery orders shared by every player: *deliver 256 oak logs for 2,560*. A player claims one (paying a deposit), delivers the items from their main inventory, and is paid the reward with the deposit back when the last unit arrives. Delivered items leave the game; they never join market stock. The [player guide](player-guide.md#the-contract-board) describes it from the player's side. Its settings are in `config/variable_economy_contracts-server.toml` ([Server config](#server-config)).

### Contract definitions

What a board can offer comes from **contract definitions**: one JSON file each, in a datapack at `data/<namespace>/contract_definitions/<path>.json`, or in `config/variable_economy_contracts/definitions/<namespace>/<path>.json`, which applies to every world and sits above every datapack. A definition's ID is its path (`<namespace>:<path>`); a file with the same ID in a higher pack, or in the config folder, replaces the one below it. NeoForge's `neoforge:conditions` work in every file, for example to load a definition only when a mod is installed. Definitions load at server start and on `/reload`. A file that can't be used is skipped with one warning in the log naming it and why.

| Field | Meaning |
| --- | --- |
| `item`, `tag` or `market_class` | Exactly one. An item ID; an item tag (with or without `#`); or a market item class (see [Item classes](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/admin-guide.md#item-classes) in the market's admin guide; a name without a namespace means `variable_economy:`, the built-in ones). A tag or class is a pool: each contract is for one item from it, picked when the board is made. Coins are never contract items. |
| `quantity` | Units one contract asks for: a whole number, or `{"min": 64, "max": 512}`, 1 to 100,000. When `max` is 64 or more, sizes are rounded down to multiples of 16, never below `min`. |
| `payout` | What each unit pays: `{"per_unit": 2.5}` (bits, decimals allowed; a contract's total is rounded down once), or `{"multiplier": 1.25}` (times the item's market `base_price`; needs the market). |
| `weight` | How often it's picked for a board, relative to the others. Default 1, 0 to 1000; 0 switches it off. |
| `stage` | Optional: a ProgressiveStages stage a player needs to claim its contracts. Players without it see them greyed out. |
| `description` | Optional notes for admins; ignored. |

```json
{
  "tag": "minecraft:logs",
  "quantity": { "min": 64, "max": 256 },
  "payout": { "per_unit": 10 },
  "weight": 3
}
```

```json
{
  "market_class": "rare_processed",
  "quantity": { "min": 4, "max": 16 },
  "payout": { "multiplier": 1.5 },
  "stage": "nether",
  "neoforge:conditions": [ { "type": "neoforge:mod_loaded", "modid": "variable_economy_market" } ]
}
```

**Built-in definitions.** With the market installed, one definition per built-in item class but `unique` (`variable_economy_contracts:market/<class>`): `common_resource` and `farmable` 64–512 units, `common_processed` 32–256, `rare_resource` and `rare_processed` 4–16, `rare_loot` 2–8, each paying 1.25 × base, weighted 40, 30, 20, 5, 5 and 3 so common items come up most. Without the market, seventeen fixed-payout vanilla definitions instead (`variable_economy_contracts:vanilla/<name>`: logs, cobblestone, sand, wheat, iron ingots and so on). To change or turn one off, put a whole definition with the same ID in the config folder or a datapack (a target, `quantity` and `payout`, as above), with `"weight": 0` to switch it off.

**Without the market,** definitions paying a `multiplier` or asking for a `market_class` are off, with one warning each at start and reload; fixed payouts work normally.

### The board

A new board replaces the old one every `board.refresh_days` in-game days (3 by default), at multiples of `board.refresh_days` in the day count. Sleeping counts. Setting the time backwards (including `/time set day`, which resets the day number) doesn't move the count back, so it can't replace a board early or keep one far past its day. With `doDaylightCycle` off, days never pass; use `/contracts admin refresh`. The board offers `board.size` open contracts (8), each for a different item: definitions are picked at random in proportion to their weight, and a tag or class definition can be picked again for another of its items.

**Claiming.** A player may hold `claims.max_per_player` contracts at once (2). Claiming takes the deposit from their active account. A held contract has `contract.duration_days` (3) from claiming to be delivered, and stays when the board is replaced: only open and finished contracts go. With `delivery.partial_allowed` off, a delivery must hand in every unit the contract still wants at once.

**Deposits.** `deposit.mode` `PERCENT` takes `deposit.amount` percent of the reward (20%, rounded up); `FLAT` takes `deposit.amount` bits whatever the contract pays. Finishing returns it all. Abandoning pays for the units delivered and returns `deposit.refund_on_abandon` percent of the deposit (50%); running out of time does the same with `deposit.refund_on_expiry` (0%). Refunds round down. Payments go to the account the deposit came from (while it exists; otherwise the player's personal account), whether the player is online or not, and an offline player is told at their next login. If the bank can't be reached at that moment, the payment is kept and paid once it can (it's tried again every 10 seconds). Turning `contracts.enabled` off settles held contracts the same way but returns the deposits whole. A deposit held counts on the leaderboard for its account, and an account holding one can't be deleted.

**Locked contracts.** A contract whose item a player hasn't unlocked (ProgressiveStages' item locks, or an add-on's; see core's admin guide), or whose definition needs a `stage` they lack, is shown to them greyed out with the reason, and can't be claimed. A contract they already hold stays deliverable if they lose the item or stage later.

### Rewards can't be gamed

With the market installed and `contracts.clamp_to_market` on (the default), every contract, fixed payout or multiplier, pays at least 1 below what buying the delivered units from the market could ever cost, so nobody profits by buying from the market to deliver, even with items bought long ago at the lowest price. The lowest an item can ever be bought for is (the fields are the market's catalog fields, in the [market's admin guide](https://github.com/The-Azotus/variable-economy-market/blob/main/docs/admin-guide.md)):

- unlimited items: `base × (1 + min_buy_markup)`, 1.5 × base with the defaults
- fixed-price items (`dynamic: false`): `base × (1 + markup)`
- other items: `base × floor × (1 + markup)`, which is about a third of base with the built-in classes
- items the market doesn't sell (`can_buy: false`), or doesn't list: no limit

Items the market doesn't buy (`can_sell: false`) are never offered: most were switched off because they could be crafted at a profit, and a contract would pay for them again. The payout is worked out again when it's made, never above what was offered, so it stays safe if you change the item meanwhile.

An item is also only offered if its contract still pays more than selling those units to the market from its normal price (factor 1.0), after `sell_fee` and the drop the sale itself causes. With the built-in classes that means mostly unlimited items, fixed-price items and items the market doesn't sell; finite items' prices can fall too far.

**Turning clamping off.** With `contracts.clamp_to_market = false`, definitions pay what they say and `can_sell: false` items are offered. That's your choice to make: the log then warns, at every start and reload, about each definition players could profit from by buying its items from the market to deliver.

### Checking definitions

At server start, after `/reload` and whenever the market's catalog changes, the log has one warning per definition that needs attention: one needing the market when it isn't installed, a tag with no items, or, while clamping is off, a definition that pays more than buying its items costs (a way to profit). While clamping is on, such a definition is only noted at info level, since its payouts are capped; the built-in ones are, for many items. Then a summary line counting the warnings. `/contracts admin definitions [page]` lists every definition with its terms, where it came from and how many of its items can be offered now, then the files the last load skipped and the warnings. `/contracts admin item <item>` names each definition that asks for the item and what its contracts pay per unit before the cap, or why it can't offer one.

## Where the money goes

Contracts have **no balance of their own**. Every payment goes through the player's bank account, never coins:

- **Payouts are new money.** Completed and partly delivered contracts create money, like market sales.
- **Kept deposits remove money.** A deposit is taken from the player's active account when they claim. What comes back (all of it on finishing, part of it on abandoning or running out of time) is paid back; the part that's kept leaves the economy.
- **Payouts go back to the account the deposit came from**, while it exists; otherwise to the player's personal account. Offline players are paid too, and told at their next login. A payment the bank can't take at that moment is owed, and paid once it can.
- **Deposits held count as the account's money.** The leaderboard counts them for the account they came from, and core's money-supply stats count them as money add-ons hold. An account holding one can't be deleted.

`/contracts admin` shows the board, each contract's holder, progress and deadline, and the lifetime totals of what contracts have paid and kept. `/bank admin stats` (core) shows the whole money supply, contracts' payouts and deposits among the sources of money created and destroyed.

## Turning contracts off

Set `enabled = false` under `[contracts]` in `config/variable_economy_contracts-server.toml`. The Contract Board then says contracts are turned off and offers none, and every held contract is settled: delivered units are paid for, and deposits are returned **whole**. Turn it back on to offer contracts again.

**Removing the add-on** from a world keeps what it holds in its own saved file (`data/variable_economy_contracts.dat`), deposits included. While it's gone that money doesn't count on the leaderboard or toward an account's holdings, so the account it belongs to can be deleted. Put the add-on back and everything returns; anything paid out to an account that has since been deleted goes to the personal account of the player it belonged to. To end contracts for good, turn them off first so every deposit is returned, then remove the add-on.

## Server config

The file is `config/variable_economy_contracts-server.toml`, created on first start. Changes apply when you save the file. A key such as `board.size` is `size` in the file's `[board]` section.

| Option | Default | Range | What it does |
| --- | --- | --- | --- |
| `access_mode` | `COMMAND_AND_BLOCK` | `COMMAND_AND_BLOCK`, `BLOCK_ONLY` | How players reach the contract board: by command and block, or by the Contract Board only; see [Letting players in](#letting-players-in). |
| `max_quantity_per_request` | 640 | 1 – 1,000,000 | Largest quantity one delivery can hand in. |
| `contracts.enabled` | true | true, false | Offer [contracts](#contracts). When off, the Contract Board says contracts are turned off and offers none; turning it off settles held contracts, paying for delivered units and returning their deposits whole. |
| `contracts.clamp_to_market` | true | true, false | With the market installed, cap every payout below what buying the delivered units could ever cost, and never offer items the market doesn't buy. Off: definitions pay what they say, with a warning at every start and reload; see [Rewards can't be gamed](#rewards-cant-be-gamed). |
| `board.size` | 8 | 1 – 64 | Open contracts on each board (fewer if not enough items qualify). Held contracts stay as well. |
| `board.refresh_days` | 3 | 1 – 365 | In-game days between boards. A new board replaces open and finished contracts; held ones stay. |
| `contract.duration_days` | 3 | 1 – 365 | In-game days to deliver after claiming. Then the contract expires. |
| `claims.max_per_player` | 2 | 1 – 64 | How many contracts one player may hold at once. |
| `deposit.mode` | `PERCENT` | `PERCENT`, `FLAT` | The deposit as a percentage of the reward (rounded up), or a fixed number of bits. |
| `deposit.amount` | 20 | 0 – 1,000,000,000 | The percentage, or the bits (fractions dropped). |
| `deposit.refund_on_abandon` | 50 | 0 – 100 | Percent of the deposit returned when the holder abandons a contract, rounded down. |
| `deposit.refund_on_expiry` | 0 | 0 – 100 | Percent of the deposit returned when a held contract's time runs out, rounded down. |
| `delivery.partial_allowed` | true | true, false | Items can be delivered in batches. Off: a delivery must hand in every unit still wanted at once (`max_quantity_per_request` doesn't limit it then), so keep definitions' sizes to what a main inventory holds. |

Core's `ignored_components` (in `variable_economy-server.toml`) also applies to deliveries: item data it lists doesn't stop a copy counting as plain.

## All commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/contracts` | everyone* | Opens the Contract Board's screen. |
| `/contracts list`, `/contracts claim <id>`, `deliver <id> <qty>`, `abandon <id>` | everyone* | The [contract](#contracts) board in chat, and the Contract Board's buttons. |
| `/contracts admin` | 2 | The whole board with holders, progress and deadlines, and the money contracts have paid and kept. |
| `/contracts admin refresh` | 2 | Replaces the board's open and finished contracts now; held ones stay. Logged in core's audit log (`/bank admin audit`). |
| `/contracts admin definitions [page]` | 2 | Every [contract definition](#contract-definitions) with its terms, where it came from and how many of its items can be offered, then the files skipped and the warnings. |
| `/contracts admin item <item>` | 2 | Each definition that asks for the item, with what its contracts pay per unit before the cap, or why it can't offer one (including when the market isn't installed). |

\* Operators only when `access_mode` is `BLOCK_ONLY`: players are told to use a Contract Board instead (see [Letting players in](#letting-players-in)).

## Troubleshooting

| You see | Meaning |
| --- | --- |
| `Contract definition … (…): …; skipped` | The file can't be used: an unknown item, a coin, a missing or out-of-range field, or a broken condition. The message says which. Lower files' definition with the same ID isn't used either. |
| `Contract definition … can't be read (…); skipped` | The file isn't valid JSON. |
| `Contract definition … (…): unknown field "…" ignored` | A misspelled or unsupported field; the rest of the file loads. |
| `Contract definition file config/variable_economy_contracts/definitions/… isn't at <namespace>/<path>.json in lower case; skipped` | Put the file in a namespace folder, with a lowercase name without spaces. |
| `Contract definition … pays a multiplier on market value, and the market add-on isn't installed, so it's off` (or `… asks for a market class, …`) | Install the market, or give the definition a `per_unit` payout and an `item` or `tag`. |
| `Contract definition … asks for …, which holds no items, so it offers nothing` | The tag is empty, usually because its mod isn't installed. An empty market class isn't warned about; `/contracts admin definitions` shows whether each definition can offer anything. |
| `Contract definition … pays more than buying … from the market costs, and contracts.clamp_to_market is off` | Players can profit by buying the items from the market to deliver. Lower the payout, or turn clamping back on. |
| `Saved contract … can't be read …, so it's dropped` | A saved contract is damaged (an unreadable item ID, status or size), usually from a hand-edited or corrupted save. If the log names a holder and deposit, refund the deposit by hand with `/bank admin money give`. A contract whose item's mod was removed isn't dropped: it stays on the board as an unknown item, can't be delivered, and expires as usual. |
| The board says *"No contracts on the board right now"* | No definition can offer an item: check `/contracts admin definitions` and `/contracts admin item <item>`. Without the market, only `per_unit` definitions for an `item` or `tag` work; with it, only items whose contract pays more than selling them to the market. Once fixed, `/contracts admin refresh` puts up a new board at once. |
| A player sees "Contracts are only available at a Contract Board on this server" | `access_mode` is `BLOCK_ONLY`. |
| The board says contracts are turned off | `contracts.enabled` is false. |
