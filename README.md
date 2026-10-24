# Printer Addon

Fabric add-on for [Litematica Printer](https://modrinth.com/mod/litematica-printer) on **Minecraft 26.2**.
Litematica Printer does the placing; the add-on builds whole schematics in survival around it:

- **One layer at a time** from the bottom, never skipping a block. Wrong blocks (wrong block, or facing the
  wrong way) are broken so the printer can place the right one.
- **Flies** above the layer area by area (16x16, from the north-west corner, back and forth inside each area, no
  area skipped). It glides on while the printer places: slower the more there is in reach (it only stops for a lot
  at once), never so fast that a block behind leaves the reach, and on to the next spot before it's even there.
  Speeding up, braking and turns are smooth; the printer only waits while it flies faster than a slow walk.
- **Automatic speed, per kind of block:** every kind (seeds, mud, pistons...) gets its own printer interval: it
  starts at your interval, goes faster while that kind comes out right (down to interval 1) and one tick slower at
  the first wrong one. Switching items that came out wrong once is done slower after. All of it is remembered per
  server (`config/printer_addon/speed-blocks.txt`) for the next build. Only the printer's own clicks count (stems
  growing, other players building don't).
- **Waits for the server's answer, not a fixed time:** the server confirms every click; a spot that was just
  clicked, and the spots next to it, wait exactly until it has. On a fast server that's shorter than before, and in
  a lag spike longer (a piston clicked against an observer the server had thrown out used to land in its spot).
- **Gets the items:** takes them from your vaults (`/pv 1`, `/pv 2`...) and the chests, barrels and shulker boxes
  you linked (no other chest is ever opened), then buys the rest in `/shop` (ShopGUI+), whole stacks per purchase
  with the buy screen's "Purchase stacks" (a stack at a time if that screen isn't understood, tried again after 10
  minutes), and crafts rails, powered rails, a diamond hoe, pumpkin seeds and redstone dust from iron and gold
  blocks, diamonds, logs, redstone blocks and pumpkins (seeds: always pumpkins bought and crafted; dust: bought as
  dust when that's cheaper, from redstone blocks when the shop can't deliver dust or "Buy blocks and craft them back"
  is on). Uses `/craft` for recipes that need a crafting table and `/feed` when hungry (both can be switched off,
  and fall back by themselves when you don't have the permission). A purchase that fails is tried again a few
  minutes later instead of never.
- **Shares the inventory between the layer's items:** each gets a part of the free slots in proportion to how many
  it still needs (as many pistons as observers: half each), so they run out together and one trip tops them all up
  (it used to fill everything with the one item that ran out first, then go back for the other one five times).
  What the layer doesn't need stays until the room is needed, then goes into storage (the next layer may need it).
- **Fast restart, also with 50+ layers:** what's finished (layers, blocks left out) is kept per server and
  schematic in `config/printer_addon/progress/`; after a restart it goes on at the first unfinished layer at once
  (no flying around to check a corner that isn't loaded, no going back for blocks it had left out). The finished
  layers are checked again in the background while it builds: a few missing blocks are placed on the way, many and
  that layer is built again first. The whole schematic is read in small pieces, so starting never freezes the game.
- **Litematica Printer places everything.** The add-on moves, breaks wrong blocks, gets the items and sets the
  printer's own settings (it starts at your interval, keeps your range). It doesn't place blocks itself; the
  optional "Add-on places blocks itself" (buckets, rails, minecarts) is off by default.
  Hopper chains are built from the hopper they end in, and your camera stays free.
- Keeps the layer's blocks on the hotbar, eats when hungry, waits when the server lags, picks up again after
  logging out, and shows the build on screen (layer, blocks left, time to go, speed, how busy the printer is and
  why not, what's missing).
- Safety: stops when you take damage or another player comes close, and never uses a nearly broken tool.

## Quick guide

**Once per server**
1. Place the schematic in Litematica.
2. `J`+`S`: scan the shop. Without a scan nothing is bought.
3. Storage: settings (`J`+`C`) → "Player vaults (/pv)", e.g. `1,2`. For a chest: look at it, `J`+`B` → "Link".

**Build**
- `J`+`B` → Start. Builds layer by layer from the bottom, gets what's missing (storage first, then the shop),
  crafts seeds, dust and rails, and puts leftovers away.
- `J`+`Q` stops everything, `J`+`A` turns the add-on off. After a disconnect or restart it goes on by itself.

**Good to know**
- Don't click in the shop or `/pv` while it's buying (closed by accident: it opens them again).
- It buys without a spending limit: keep an eye on your money.
- `/feed` and `/craft` are used if you're allowed to (settings: "Eat with /feed", "Craft with /craft").
- It stops when you take damage or another player comes close (settings → Safety).
- Something wrong? `J`+`B` → "Open build logs folder" and send the newest log.

## Build log

Every auto build writes its full log to `.minecraft/logs/printer_addon/build-<date>_<time>.log` (the last 20
are kept; `J`+`B` → "Open build logs folder"). Send it when something goes wrong. Lines by kind:

| Kind | What |
|---|---|
| `START`, `SETTINGS`, `INVENTORY` | Versions, every setting (add-on and printer), reach, ping, storage, the whole inventory (also once a minute) |
| `TRY` | Every click of the printer: the spot, what's there, what the schematic wants, the item it clicks with, the item in hand, the distance, where the player is |
| `PLACED`, `WRONG`, `NOCHANGE` | The result of every click once the server answered: right, wrong (or landed next to it) and what it should have been, or nothing placed; with the item, its interval and the item switched from |
| `HOTBAR` | Every switch to another item before a click (and how often that switch went wrong before), clicks with an empty hand, once a minute a summary per switch |
| `SPEED`, `SPEEDS` | Every speed change of a kind of block; once a minute and per layer the table of all of them (interval, right, wrong, placed nothing) |
| `SKIP`, `RETRY`, `GIVEUP`, `IGNORED` | Every block left for later, with the reason and how often; tried again as soon as nothing else is left; left out of the layer after the 4th time (tried again at the end); at the end the full list of what wasn't placed and why |
| `CONFLICT` | Blocks of the player's own buildings where the schematic wants something else: left alone, never broken |
| `MOVE`, `STATE`, `PLAN` | Every flight (from, to, how long, blocks in reach on arrival), every change of what the player does, the spots planned per area |
| `TURNS` | Every second: what the printer's turns went to (placed, paused and why, nothing in reach...), how many blocks the mover and the printer see in reach, what the player does and where |
| `SCAN`, `LAYER`, `SNAP`, `STUCK`, `WAIT` | What's left to do, what waits for items, what isn't loaded; every 5 s a summary; when nothing is placed for a while, the nearest unfinished blocks and why |
| `LEARN`, `PERF` | What it learned about the reach on this server; how many ms each part of the add-on took |
| `PLAN` (inventory) | How the inventory is shared: each item held/target, the building slots, what a trip gets, what's put away to make room |
| `STORAGE`, `SHOP`, `CRAFT`, `EAT` | Every vault/chest opened (what it was for, what was taken or put away, what's left in it, closed by you and opened again), every shop purchase and the screen when one fails, the craft and feed commands |
| `VERIFY` | Finished layers checked again in the background: blocks missing there and what's done about it |
| `step`, `detail`, `info`, `WARN` | Everything else it does or says |

## Install

Put these in `.minecraft/mods` (or the Modrinth profile's `mods` folder), together with this mod's jar:

| Mod | Version |
|---|---|
| Fabric Loader | 0.19.3 or newer |
| Fabric API | for 26.2 |
| MaLiLib | 0.29.6 |
| Litematica | 0.28.8 |
| Litematica Printer | 3.2.2 (26.2) |
| Mod Menu | optional (settings button in the mod list) |

## Use

1. Load and place a schematic in Litematica as usual. The add-on says in chat what it still needs and what
   that costs in the shop (scan the shop once with `J`+`S`).
2. `J`+`B` opens the build menu: start or stop auto build, link the chest you're looking at (look at it first,
   then press `J`+`B`), build report, cost, shop scan. Auto build turns the printer on and builds from the
   bottom layer up. `J`+`Q` stops everything.
3. `J`+`C` (or Mod Menu) opens the settings.

| Keys | What |
|---|---|
| `J`+`B` | Build menu: start / stop, link chests, report, cost, shop scan |
| `J`+`Q` | Stop everything (also in menus) |
| `J`+`A` | Add-on on / off (off: Litematica Printer works as without it) |
| `J`+`C` | Settings |
| `J`+`P` | What the build still needs and what it costs |
| `J`+`R` | Build report: everything missing or wrong, with coordinates (`config/printer_addon/report.txt`) |
| `J`+`S` | Scan `/shop` |
| `J`+`F` | Fix wrong blocks around me |
| `J`+`X` | Clear extra blocks around me |
| `J`+`Page Up` / `Page Down` | Layer up / down |

All keys can be changed in the settings' Hotkeys tab.

## Build

Needs Java 25.

```
./gradlew build
```

The jar ends up in `build/libs/`.

## License

AGPL-3.0, same as Litematica Printer.
