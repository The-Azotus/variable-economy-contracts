# Changelog

What changed in each release of the contracts add-on, for players and server admins. Mod and add-on authors: the API's changes are in the [add-on guide's changelog](docs/api.md#changelog). Core's changes, and the whole of each Variable Economy release, are in [core's changelog](https://github.com/The-Azotus/variable-economy-core/blob/main/CHANGELOG.md).

## Unreleased

**Install with core 0.6.3 or a later 0.6.x.** The network protocol is unchanged.

- **Whole deliveries over the limit work from the screen.** With `delivery.partial_allowed` off, the one delivery that finishes a contract may be larger than `max_quantity_per_request`, as the server always allowed; the screen, All and the +/- buttons now allow it too.
- **The list's scrollbar can be dragged**: click it to jump there, or hold and drag.
- **The wide panel has no blank gap**: the selected contract's details, the quantity and the buttons sit together. Until you select a contract, the panel shows how contracts work, and the quantity and buttons are hidden.
- The note under the quantity ("You have none") clears as soon as the quantity changes.
- A request from the board by a player who has just died is ignored.
- **Holders' names show** for players the server's name cache has forgotten (it keeps only the 1,000 seen most recently), using the names core remembers.
- A holder's name longer than 16 characters (possible behind some proxies) no longer stops the board being sent.

## 0.6.1 (2026-11-17)

- Contracts now live in their own repository; nothing changes in game.
- The mod's jar no longer contains its test code.

## 0.6.0 (2026-11-14)

The mod was called Dynamic-Economy until this release. It is now **Variable Economy**, and it is four mods instead of one; contracts is one of them. The whole release is described in [core's changelog](https://github.com/The-Azotus/variable-economy-core/blob/main/CHANGELOG.md).

**Before you update:** worlds from Dynamic-Economy can't be carried over. The mod's id changed, so its contracts, bank balances, coins and terminals don't load in a 0.6.0 world. Start a new world.

### An add-on of its own

- **Contracts** (`variable_economy_contracts`) is an optional add-on. Install it on top of core, with or without the market and the exchange.
- Install the same mods on the server and on every client, all of the same version. A client missing an add-on the server has (or the other way round) can't join.
- Contracts has its own block and screen, the **Contract Board**, in place of the one tabbed Market Terminal.
- It has its own config file (`config/variable_economy_contracts-server.toml`), its own `access_mode` and its own command, `/contracts`. Its admin commands moved with it, to `/contracts admin`.

### Contracts

- Contracts come from **contract definitions**: one JSON file each, in a datapack or in `config/variable_economy_contracts/definitions/`. A definition asks for an item, an item tag or (with the market) a market item class, in a quantity range, paying a fixed amount per unit or a multiplier on the market price. The `contract_*` catalog fields are gone.
- **Built in:** with the market, one definition per item class, as before. Without the market, seventeen fixed-payout vanilla contracts, so core and contracts alone have a board.
- **Claims:** each player may hold two contracts. A claimed contract has its own time limit (3 days by default) and stays when the board refreshes (every 3 days by default).
- **Deposits** are a percentage of the payout or a flat amount. Finishing returns all of it; abandoning returns half by default, and running out of time none. Either way you're paid for what you've delivered so far.
- Admins can require **whole deliveries** instead of deliveries in parts.
- **Locked contracts** (an item you haven't unlocked, or a stage you lack) are shown greyed out with the reason, instead of being hidden.
- With the market, every payout is capped just below what buying the items would cost, so contracts can't be farmed from the market. Admins can turn the cap off (`contracts.clamp_to_market`); the log then warns about every definition players could profit from.
- Deposits held count on the leaderboard for the account they came from.
