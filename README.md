# Printer Addon

Fabric add-on for [Litematica Printer](https://modrinth.com/mod/litematica-printer) on **Minecraft 26.2**.
Litematica Printer does the placing; the add-on builds whole schematics in survival around it:

- **One layer at a time** from the bottom, never skipping a block. Wrong blocks (wrong block, or facing the
  wrong way) are broken so the printer can place the right one.
- **Flies** above the layer area by area (16x16, from the north-west corner, back and forth inside each area, no
  area skipped), always to where the most blocks can be placed at once, and moves on as soon as nothing in reach
  is left to place.
- **Automatic speed:** starts at your printer interval, goes faster while every block comes out right (down to interval 1) and slows
  down at the first wrong one. Remembers the speed per server, and per kind of block, for the next build.
- **Gets the items:** takes them from the chests, barrels and shulker boxes you linked (no other chest is ever
  opened), then buys the rest in `/shop` (ShopGUI+), and crafts rails, powered rails and pumpkin seeds from
  iron and gold blocks, logs, redstone and pumpkins. Only what the current layer needs is bought; leftovers go
  back into the linked chests when the inventory gets full.
- **Litematica Printer places everything.** The add-on moves, breaks wrong blocks, gets the items and sets the
  printer's own settings (it starts at your interval, keeps your range). It doesn't place blocks itself; the
  optional "Add-on places blocks itself" (buckets, rails, minecarts) is off by default.
  Hopper chains are built from the hopper they end in, and your camera stays free.
- Keeps the layer's blocks on the hotbar, eats when hungry, waits when the server lags, picks up again after
  logging out, and shows the build on screen (layer, blocks left, time to go, speed, how busy the printer is and
  why not, what's missing).
- Safety: stops when you take damage or another player comes close, and never uses a nearly broken tool.

## Build log

Every auto build writes its full log to `.minecraft/logs/printer_addon/build-<date>_<time>.log` (the last 20
are kept; `J`+`B` → "Open build logs folder"). It has every step and message (what went wrong, why it waits,
wrong blocks, buying), every flight (from where to where, how long it took, how many blocks were in reach on
arrival), a snapshot every 5 seconds (position, layer, blocks left, blocks placed in those 5 seconds, what it's
doing, how busy the printer is and why not), and once a minute how many milliseconds each part of the add-on
took.

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
