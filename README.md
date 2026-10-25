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

It builds layer by layer from the bottom, flying over each layer in straight lanes while the printer places all the way. With "Build layers together" (on by default), a layer whose blocks stand on the layer below, like seeds on farmland, redstone dust on observers and pistons, cactus on sand, or sand on string, is built together with that layer in the same lanes. Cactus always go with the sand above them, never with the layer below: a cactus left open on top until the next pass grows a block or two and gets a flower. Lanes stop at tall walls, such as the walls of a farm: it finishes one side lane by lane, then the other, and doesn't fly over parts that are already built. A lane whose middle row has cactus (or anything else) standing up into the flight height is flown along the row beside it, so the player never rises over them and keeps the layer in reach. If you're kicked or the server restarts, it joins again, runs your "Commands back to the build" and carries on. The same happens when the server times out and the network moves you to its lobby: by default it types `/server Skyblock`, which puts you back where you were. Starting it again also retries the blocks it skipped last time.

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

## Auto break

Breaks everything in an area, for example a farm you want gone:

1. Select the area with Litematica's area selection (its selection tool: corner 1 and corner 2).
2. Press `J`+`B` and open the **Auto break** tab. It shows the area and every kind of block in it with how many there are. Click a kind to leave it standing (`skip`, in red), click it again to break it.
3. Click the green **START Auto break**. Stop it with the red **STOP Auto break** (the same button) or `J`+`Q`.

It breaks the top level first and works down, the nearest blocks first, each with your fastest tool for it (Efficiency counts, your own enchanted tools too, never one about to break). It flies to a free spot within reach of each block (never into a cactus) and stands where it can, since breaking in the air is 5 times slower. A cactus, sugar cane, bamboo, flower or carpet that stands on a block it breaks isn't broken itself: it drops with that block, so a cactus column on its sand is one break. It never breaks containers ("Never break containers"), liquids, what can't be broken, or a block that holds back lava.

Drops: with "Auto break: put drops away" on (the default), once the inventory is full what was broken goes into your vaults (`/pv`) or linked chests; off, the drops are ignored and breaking goes on. The build log (`AREA` lines) has each level's time and the end result.

## Sharing a log

Every auto build and Auto break writes a log in `logs/printer_addon/`, named by when, what and which version, so the newest sorts last: `2026-10-25 16.45.12 auto build Pumpkin_farm v0.15.1.jsonl`.

**Report / share log** in the J+B menu opens a short form: write what went wrong (or leave it empty), click what fits (skips blocks, too slow, gets stuck, shop, tilling, error...), and check what you were building (filled in from your schematic). A screenshot you took with F2 in the last 15 minutes can go with it. **Send to the developer** sends it to the add-on's developer (a Discord channel) with the run's log (the one going on, else the last one) and the full debug:

- `debug.txt`: what you wrote, where you are and what's running, your hotbar and inventory, PC (OS, Java, CPU, memory, graphics card, window, fps), every mod and its version, every setting of the add-on, Litematica Printer and Litematica, the schematic placements, recent runs and learned speeds.
- `game-log.txt`: the game's own log (`logs/latest.log`, errors from any mod) without chat lines.
- `crash-report.txt`: a crash report from the last day, if there is one.

Nothing private goes: no chat, no Log channel, no chest or vault spots, and your user folder (it has your name in it) is written `~`. The run's log has the build's coordinates, your inventory and your balance. What you wrote is kept until it's sent. Nothing is sent unless you click Send, or turn on **Share logs automatically** (then every run's log and debug go when the run ends). **Log channel** in the settings sends them to your own Discord webhook instead.

## Building the jar

The developer's log channel (a Discord webhook) goes into the jar from `src/main/resources/printer_addon/log-channel.txt` (the URL reversed, Base64), which is never committed: a webhook URL in a public repository gets found and spammed. Without the file the jar builds the same, and "Report / share log" asks for a Log channel.

## Good to know

- Don't click in the shop or `/pv` while it's buying.
- At start it asks how much it may spend in the shop (or click "No limit").
- It uses `/feed` and `/craft` if you have permission.
- It stops when you take damage or another player comes close. Brushing a cactus of the build doesn't stop it while you have more than half your health. It keeps your body clear of cactus and berry bushes while flying: the ways it flies and the spots it hovers at keep some room from them, and when one is about to touch you anyway (a cactus that grew a block where its sand was missing), it rises over it.
- Blocks with nothing beside or under them to place them against, like the string at the bottom of a cactus farm, are placed in the air. If the server throws those blocks out, it stops doing that and tells you.
- The printer interval never goes below 2. The speed guard watches how fast the server answers each click. A click that gets no answer for 2 seconds while the server goes on as usual was dropped by the server: it's forgotten and placing goes on. Only when the server goes quiet too does placing wait; then it goes on at no more than 8 blocks a second until the answers come as fast as before, and full speed again right after. Every 10 seconds the build log gets a SERVER line with blocks a second, answer times (p50, p95, max), the server's ticks per second, the ping and the packets sent, and each slowdown ends with a GUARD-END line saying what it cost.
- It doesn't craft a diamond pickaxe or shovel when you have a netherite or diamond one of your own.
- Farmland: the dirt goes in, is tilled with your hoe (a plain one first) and gets its stem or seeds in the same pass when that layer is built together with it. Dirt the printer's hoe missed is tilled by the add-on itself, checked, and tried up to 3 times. When it starts, the finished layers are checked for farmland that's still dirt and stems that are missing: the build log lists them per layer (AUDIT), and they're done when you're near them and at the end.
- Lanes don't leave holes behind: a block about to leave the reach that a moment more gets in (its click not answered yet, or the printer can do it now) holds the lane for up to 1.5 s.
- Blocks on the "Always break these blocks" list (cactus_flower by default) are broken wherever they are in the schematic's area, also where the schematic has air, and are never taken for your own building. A flower on top of a cactus stops that cactus from growing for good. A cactus that grew up (with or without a flower on top) drops by itself once the sand beside its column goes in, so neither it nor its flower is broken by hand or flown to. A cactus that grew into the spot of a block the layer still needs, or next to a cactus of the layer (a cactus can't be placed next to another), is broken. Of a column of wrong cactus, only the lowest is broken, and the rest falls with it. When a wrong block is left alone, the build log says why (BREAK-SKIP).
- Breaking is 5 times slower while flying, so for a slow block (a cactus: 3.3 seconds flying, under a second standing) it lands on a block up to two blocks away, breaks it standing and flies on ("Stand while breaking", on by default). With nothing to stand on near it, it breaks from the air, and the build log says why (`BREAK from the air`). A layer that is mostly breaking, or has only a few blocks, is done spot by spot instead of in lanes.
- Cactus flowers you pick up from breaking (the drops of the "Always break these blocks" list, when the schematic has none of them) are junk: they're thrown out while a vault is open on a supply trip, or sold if your shop buys them back and "Sell leftovers" is on. When a supply trip is short of room, blocks this build doesn't use at all (your own iron bars, concrete...) go into the vault too, so every purchase fills whole stacks; tools, food, valuables and anything renamed or enchanted stay.
- When the server lags hard (under 10 ticks a second, or silent for 2 seconds), no supply trip starts, and one under way waits for the shop and vault menus instead of giving up. An item the shop didn't deliver is tried again after 30 seconds, then 1 and 2 minutes; the build only stops for it after 3 failed tries.
- It never clicks against a cactus flower or a cactus about to fall, and holds a block back while it can't stand there yet (a cactus next to a wrong block). Skipped clicks are in the build log (CLICK-SKIP). Sand waits until the string under it is confirmed (it would fall); other blocks don't wait for the block they're placed against (that made a test farm 10 % slower for the few clicks a server refuses).
- Your own blocks standing where the schematic wants something else are left alone. The build log and chat sum them up once, instead of listing every block.
- It never flies past the world border, such as the edge of your island.
- With the add-on off, Litematica Printer works as normal.
- A farm that's already running (pistons firing) doesn't hold up a layer. Pumpkins or melons that grew where a stem belongs are broken, so the stem can be planted.
- If the server won't let it craft a tool, it goes on without it. Once the server has refused two recipes put in by hand, it only crafts what's in your recipe book there, and remembers that between games.
- A container or unbreakable block of yours where the schematic wants something else (a hopper where redstone goes) doesn't hold up the layer. It's listed at the end for you to fix by hand.
- If nothing has been placed for 30 seconds while building, it plans its route again and leaves the stuck spot for later.
- Something wrong? Press `J`+`B`, click "Open build logs folder" and send the newest `.jsonl` file.

## The build log

Each run gets a build log in `.minecraft/logs/printer_addon/` (`<date> <time> auto build <schematic> v<version>.jsonl`, or `auto break`): one JSON object a line, easy to read by a program. With "Text build log" on, the same lines are also written as plain text (the same name, `.log`). The last 20 are kept. The run's own lines use fixed `key=value` fields, times in seconds (`s`) or ticks (`t`, 20 a second), and every tick of the run counts in exactly one category: placing, lane-flying, transit, buying, vault, crafting, guard-throttle, server-wait, item-settle, breaking, stuck or idle-no-work.

- `RUN-START`: the run's id, the version, the server and the settings.
- `BEAT`: every 10 seconds, what happened: blocks placed, clicks and misses in those 10 s, blocks a second, what the time went to, the lane, the position, the speed, the height over the layer, the blocks in reach, held back, not ready yet and offered to the printer, the pause reason, ticks per second, ping, fps, free slots and how many of the layer's items you have.
- `LANE-END` and `LANE-LEFT`: at the end of each lane, the blocks planned, placed and left, the time and blocks a second, the pauses by category, the height and speed, the clicks, misses and item switches, and each block left with the reason (out of reach when passing, held back, not ready, clicked without result, given up, no item, conflict).
- `LAYER-END`: per layer, the time, the blocks per item, blocks a second in the lanes and on the leftovers, the leftovers and their time, flights and distance, the time by category and the top 3 time thieves. `LAYER-DONE` is the short version for comparing layers: time, blocks, blocks a minute, leftovers and their time, buying time and misses.
- `LEFTOVER-PLAN`: after the lanes, the route over what's left: areas, blocks, the route's length and how long nearest-first alone would be.
- `TRIP`: each flight that isn't along a lane, with its reason, from, to, distance, time, highest point, blocks placed on the way and placeable on arrival.
- `NOCHANGE`, `WRONG` (and `PLACED` with "Debug log"): the result of a click, with the side and spot clicked, the head's turn, the player's speed, the distance from the eye (here and as the server has it), the ticks since the last item switch (when under 20) and the server's answer time. A click without effect also gets its likely cause; a refused one says what was likely behind it: `refused-same-tick-as-switch`, `refused-during-lag`, `refused-falling-block-on-new-block`, `refused-against-new-block`, `refused-moving-far` or `refused-other`.
- `HOLD`: a block held back from the printer for a second or more, and why.
- `SUPPLY-START`, `BUY`, `STOCK-END`, `SUPPLY-END`: each trip for items, from what started it (and where in the lane it was) to the purchases (item, count, price, clicks, time), the bulk buying, the free slots in the inventory and the vaults before and after, and what fills the inventory's slots (`slots=free=.. build=.. buildPartial=.. junk=.. notForBuild=.. tools=.. other=..`).
- `SERVER` and `GUARD-END`: see the speed guard above.
- `HOTBAR-MIN`: every minute, the item switches by pair, the clicks right after a switch and how many of those missed, and the time spent waiting for swaps.
- `STUCK` and `STUCK-SUPPRESSED`: nothing placed for 30 seconds (with the nearest unfinished blocks and why), or nothing placed for a reason that isn't being stuck (the server lagging, a trip for items), which doesn't count.
- `EVENT` and `USER`: damage (which block, health, food), disconnects, and what you did while it ran (a setting changed, the layer changed by hand, stopping it).
- `BREAK-SKIP` and `CLICK-SKIP`: a wrong block left alone and why, and a click not made (against a block about to go).
- `JUNK`: cactus flowers thrown out or sold.
- `RUN-ADVICE`: right after RUN-END, the three biggest costs of the run, each with what's behind it (buying, leftovers, breaking, waiting for the server, flying, nothing to place, missed clicks).
- `RUN-END` and `RUN-COMPARE`: totals, blocks a minute and a second, the top 5 time thieves, failed clicks by cause, wrong and given-up blocks, money spent and per block, cactus touches and lifts, the server's answers to clicks, breaks (standing and flying, ticks each) and junk thrown out; then what got better and worse since the last run on the same server, in seconds per 1000 blocks.

Numbers always have a decimal point (`2444.3`), whatever the language of your computer; block spots are `x,y,z`. A line for every block tried or placed, every item switch and the older status lines (`TRY`, `PLACED`, `TURNS`, `SNAP`, `SCAN`, `STEP`, `DETAIL`) are only written with "Debug log" on: an hour of building was 19.5 MB, 70 % of it those lines. The `LAYER` line comes when something changed, at most every 10 seconds, and the list of conflicts when they're first found and at the end. A line that repeats is written once, followed by `REPEAT ×N`. The runs are kept in `config/printer_addon/runs.txt` for the comparison, and what your vaults hold in `config/printer_addon/storage.txt`.

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
