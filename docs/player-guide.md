# Player guide

The contracts add-on for Variable Economy puts up a **Contract Board**: delivery orders shared by every player on the server. Claim one, deliver the items and get paid.

Everything is paid through your bank account, so you don't need coins in your inventory. Deposits are taken from the account you're using for trades (your *active account*), and payouts go back to it. Coins go in and out of the bank at a Bank block or with a Bank Card; see [core's player guide](https://github.com/The-Azotus/variable-economy-core/blob/main/docs/player-guide.md) for the bank, your accounts and the coins. Money is counted in **bits**.

## The Contract Board

Right-click a **Contract Board** block to open the board. Anyone can craft one (see [Crafting the Contract Board](#crafting-the-contract-board)). If you walk more than about 8 blocks away from the block you opened, its screen closes.

`/contracts` opens the same screen from anywhere, if your server allows it. If you see *"Contracts are only available at a Contract Board on this server"*, find the block instead.

The server puts up a **board** of contracts: orders for a number of one item, such as *256 × Oak Log*, each with a reward. Everyone sees the same board, and a new one replaces it every few in-game days (3 by default; nights you sleep through count). The heading says how long the current one has left, and how many contracts you hold out of the most you may (2 by default).

1. **Claim** a contract to lock it to you. This takes a **deposit** (a fifth of the reward by default) from the account you're using. Nobody else can claim it while you hold it, and you have a few in-game days to deliver (3 by default); it stays yours when a new board goes up.
2. **Deliver** the items from your main inventory, in as many batches as you like (some servers want them all at once): set the quantity and click **Deliver**. **All** fills in what you hold, up to what's still wanted. Delivered items are used up; they don't go to the market.
3. The **last unit pays the reward** and returns the deposit.

If you **Abandon** the contract (click twice), you're paid for the units you delivered and get part of the deposit back (half by default); the button's tooltip says how much. If its time runs out first, you're paid for what you delivered, and by default the deposit is lost. If that happens while you're offline, the money still goes to your bank and you get a message when you next log in. If you claimed with a shared or party account, the payment goes back to that account (or to your personal account, if it has ended).

Each row shows the item and amount, the reward, and **Open**, **Locked**, who holds it, your progress and days left (*120/256, 2d*), **Done** or **Abandoned**. Click one to see its reward, the reward per unit, the deposit, progress and how many you have. If the server has turned contracts off, the board says so.

**What you can deliver.** Only plain items from your main inventory (hotbar and backpack rows) count: enchanted, damaged or renamed copies don't, and neither do armour, offhand or modded slots.

**Rewards.** On servers with the market, a contract always pays less per unit than the market could ever sell you that item for, so buying from the market to fill one loses money. It pays more than selling the same items to the market at their normal price, so if you gather or farm the items yourself, a contract is the better deal.

**Locked contracts.** Contracts you can't claim yet, for an item you haven't unlocked or needing a stage you don't have (ProgressiveStages), show greyed out as **Locked**, with the reason in the tooltip; one you've already claimed stays yours even if you lose the stage.

**Commands.** `/contracts list` lists the board in chat; `/contracts claim <id>`, `deliver <id> <amount>` and `abandon <id>` do the same as the buttons, where the server allows commands.

## Messages

| Message | Meaning |
| --- | --- |
| *Claimed the item contract; X deposit taken* | Done; now deliver. |
| *Delivered N × item* | Done; deliver the rest before the contract's time runs out. |
| *Finished the item contract: paid X* | The reward and your deposit are in the bank. |
| *Abandoned the item contract; paid X for what you delivered and part of the deposit* | Done; the rest of the deposit is lost. |
| *Your item contract ended before you finished it: …* | Its time ran out (or the server turned contracts off) while you were away. The message says what you delivered, what you were paid and what happened to the deposit. |
| *Someone else has claimed that contract, or it's finished* | Pick another one. |
| *That contract has expired* | Its time ran out, or the board changed while you had the screen open. |
| *You haven't unlocked that contract yet* | The item, or the stage it needs, isn't unlocked for you. |
| *Deliver every unit the contract still wants at once* | This server doesn't take deliveries in batches. |
| *You already hold as many contracts as you may* | Finish or abandon one first. |
| *That's more than the contract still wants* | Deliver fewer. |
| *You don't hold that contract* | Only the player who claimed it can deliver or abandon it. |
| *You don't have that many (enchanted, damaged or renamed copies don't count)* | You hold fewer plain copies than you tried to deliver. |
| *Your bank account can't cover that* | The account you're using can't pay the deposit. |
| *That order is too large* | Deliveries are capped (640 by default). Split it up. |
| *Enter a quantity of at least 1* | Set how many to deliver. |
| *The bank is unavailable right now* | The server's bank can't be reached. Tell an admin. |
| *Contracts are turned off on this server* | Ask an admin. |
| *Contracts are only available at a Contract Board on this server* | The server keeps contracts to their block. |

## Crafting the Contract Board

The Contract Board is 8 Bits around a book:

```
Bit  Bit   Bit
Bit  Book  Bit
Bit  Bit   Bit
```

Your server may use a different recipe; check a recipe viewer if this one doesn't work. The block faces you when you place it. Break it by hand to pick it up again; it breaks faster with an axe.
