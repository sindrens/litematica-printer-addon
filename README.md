# Printer Addon

Builds whole Litematica schematics in survival for you, on top of Litematica Printer (Minecraft 26.2).
The printer places the blocks; the add-on flies you around, fixes wrong blocks and gets what's missing from your vaults, chests and `/shop`.

## Install

Put these in your `mods` folder:

| Mod | Version |
|---|---|
| Fabric Loader + Fabric API | 0.19.3+ / for 26.2 |
| MaLiLib | 0.29.6 |
| Litematica | 0.28.8 |
| Litematica Printer | 3.2.2 |
| Printer Addon | this jar |

## Get started

1. Place your schematic in Litematica.
2. Press `J`+`S` once per server to scan the shop.
3. Open the settings with `J`+`C` and enter your vault numbers under Important, e.g. `1,2`.
4. To use a chest, look at it, press `J`+`B` and click "Link".
5. Press `J`+`B` and click Start.

It builds layer by layer from the bottom, flying over each layer in straight lanes while the printer places all the way. With "Build layers together" (on by default), a layer whose blocks stand on the layer below, like seeds on farmland, redstone dust on observers and pistons, cactus on sand, or sand on string, is built together with that layer in the same lanes. Lanes stop at tall walls, such as the walls of a farm: it finishes one side lane by lane, then the other, and doesn't fly over parts that are already built. If you're kicked or the server restarts, it joins again, runs your "Commands back to the build" and carries on. The same happens when the server times out and the network moves you to its lobby: by default it types `/server Skyblock`, which puts you back where you were. Starting it again also retries the blocks it skipped last time.

Your inventory can start empty. It takes what it needs from your vaults and linked chests and buys the rest. Before a lane it tops up what the lane needs, and on the last shop trip of a layer it also buys for the next one. Anything that costs under $50,000 a stack is bought in whole stacks; what's left over goes into your vaults.

With "Buy in bulk into vaults" (on by default), when a trip has to buy something your vaults are out of, it buys a whole inventory of it in one purchase and puts it in your vaults (one purchase per item; with little room in your inventory, the layer's items go into the vaults first and come back after). The next trips only open a vault, which takes about 2 seconds instead of about 14 in the shop. It never buys more than the build still needs, and it always leaves at least 15% of your vault slots free (9 at least) for what's put away at a layer change.

It remembers what's in each vault and linked chest after opening it, also for the next time you play (in `config/printer_addon/storage.txt`, per server). A new session still looks in each one once, since you may have changed them.

It uses plain diamond tools (hoe, pickaxe, shovel, axe) until they break. If there's none in your inventory, vaults or chests, it crafts one from diamonds, or from a block of diamond it buys. Your own tools (enchanted, renamed or netherite) are used only when there's nothing else, and are put away before they break.

When you stop it, or the build is done, it puts the building items back in your vaults. It warns you when they're getting full; link a chest for more room. You can clear out your vaults at any time, because it buys whatever is missing.

## Keys

| Key | What it does |
|---|---|
| `J`+`B` | Build menu: start/stop, put items away, add-on on/off, link chests, logs |
| `J`+`C` | Settings |
| `J`+`S` | Scan the shop |
| `J`+`Q` | Stop everything |
| `J`+`A` | Add-on on/off |

## Good to know

- Don't click in the shop or `/pv` while it's buying.
- At start it asks how much it may spend in the shop (or click "No limit").
- It uses `/feed` and `/craft` if you have permission.
- It stops when you take damage or another player comes close. Brushing a cactus of the build doesn't stop it while you have more than half your health. It keeps your body clear of cactus and berry bushes while flying: when one is about to touch you (a cactus that grew a block where its sand was missing), it rises over it.
- Blocks with nothing beside or under them to place them against, like the string at the bottom of a cactus farm, are placed in the air. If the server throws those blocks out, it stops doing that and tells you.
- The printer interval never goes below 2. The speed guard watches how fast the server answers each click. A click that gets no answer for 2 seconds while the server goes on as usual was dropped by the server: it's forgotten and placing goes on. Only when the server goes quiet too does placing wait; then it goes on at no more than 8 blocks a second until the answers come as fast as before, and full speed again right after. Every 10 seconds the build log gets a SERVER line with blocks a second, answer times (p50, p95, max), the server's ticks per second, the ping and the packets sent, and each slowdown ends with a GUARD-END line saying what it cost.
- It doesn't craft a diamond pickaxe or shovel when you have a netherite or diamond one of your own.
- Blocks on the "Always break these blocks" list (cactus_flower by default) are broken wherever they are in the schematic's area, also where the schematic has air, and are never taken for your own building. A flower on top of a cactus stops that cactus from growing for good. A cactus that grew where the schematic has air breaks by itself once the sand beside it goes in, so it isn't broken by hand. Of a column of wrong cactus, only the lowest is broken, and the rest falls with it.
- Your own blocks standing where the schematic wants something else are left alone. The build log and chat sum them up once, instead of listing every block.
- It never flies past the world border, such as the edge of your island.
- With the add-on off, Litematica Printer works as normal.
- A farm that's already running (pistons firing) doesn't hold up a layer. Pumpkins or melons that grew where a stem belongs are broken, so the stem can be planted.
- If the server won't let it craft a tool, it goes on without it. Once the server has refused two recipes put in by hand, it only crafts what's in your recipe book there, and remembers that between games.
- A container or unbreakable block of yours where the schematic wants something else (a hopper where redstone goes) doesn't hold up the layer. It's listed at the end for you to fix by hand.
- If nothing has been placed for 30 seconds while building, it plans its route again and leaves the stuck spot for later.
- Something wrong? Press `J`+`B`, click "Open build logs folder" and send the newest log.

## The build log

Each run gets a build log (`.minecraft/logs/printer_addon/build-<date>.log`) and, next to it, the same lines as JSON (`.jsonl`) for reading by a program. The run's own lines use fixed `key=value` fields, times in seconds (`s`) or ticks (`t`, 20 a second), and every tick of the run counts in exactly one category: placing, lane-flying, transit, buying, vault, crafting, guard-throttle, server-wait, item-settle, breaking, stuck or idle-no-work.

- `RUN-START`: the run's id, the version, the server and the settings.
- `SEC`: every second, what happened: blocks placed, clicks, failed clicks, blocks a second over the last 10 s, what the time went to, the lane, the position, the speed, the height over the layer, the blocks in reach, held back, not ready yet and offered to the printer, the pause reason, ticks per second, ping and fps.
- `LANE-END` and `LANE-LEFT`: at the end of each lane, the blocks planned, placed and left, the time and blocks a second, the pauses by category, the height and speed, and each block left with the reason (out of reach when passing, held back, not ready, clicked without result, given up, no item, conflict).
- `LAYER-END`: per layer, the time, the blocks per item, blocks a second in the lanes and on the leftovers, the leftovers and their time, flights and distance, the time by category and the top 3 time thieves.
- `TRIP`: each flight that isn't along a lane, with its reason, from, to, distance, time, highest point, blocks placed on the way and placeable on arrival.
- `PLACED`, `NOCHANGE`, `WRONG`: the result of each click, with the side and spot clicked, the head's turn, the player's speed, the distance from the eye (here and as the server has it), the ticks since the last item switch and the server's answer time. A click without effect also gets its likely cause.
- `HOLD`: a block held back from the printer for a second or more, and why.
- `SUPPLY-START`, `BUY`, `STOCK-END`, `SUPPLY-END`: each trip for items, from what started it (and where in the lane it was) to the purchases (item, count, price, clicks, time), the bulk buying, and the free slots in the inventory and the vaults before and after.
- `SERVER` and `GUARD-END`: see the speed guard above.
- `HOTBAR-MIN`: every minute, the item switches by pair, the clicks right after a switch and how many of those missed, and the time spent waiting for swaps.
- `EVENT` and `USER`: damage (which block, health, food), disconnects, and what you did while it ran (a setting changed, the layer changed by hand, stopping it).
- `RUN-END` and `RUN-COMPARE`: totals, blocks a minute and a second, the top 5 time thieves, failed clicks by cause, wrong and given-up blocks, money spent and per block; then what got better and worse since the last run on the same server, in seconds per 1000 blocks.

A line that repeats is written once, followed by `REPEAT ×N`. The runs are kept in `config/printer_addon/runs.txt` for the comparison, and what your vaults hold in `config/printer_addon/storage.txt`.

## Watch the speed test

The test builds a 30×30 copy of a pumpkin farm in a new test world and prints the time for each layer. With JDK 25, in the project folder (Windows):

```
set PRINTER_ADDON_ONLY=FarmTest
set PRINTER_ADDON_SCHEMATIC=%CD%\testdata\farm-30x30.litematic
set PRINTER_ADDON_PREBUILT_LAYERS=0
set PRINTER_ADDON_WATCH=1
gradlew runClientGameTest
```

On Linux or macOS:

```
export PRINTER_ADDON_ONLY=FarmTest PRINTER_ADDON_SCHEMATIC=$PWD/testdata/farm-30x30.litematic PRINTER_ADDON_PREBUILT_LAYERS=0 PRINTER_ADDON_WATCH=1
./gradlew runClientGameTest
```

AGPL-3.0, same as Litematica Printer.
