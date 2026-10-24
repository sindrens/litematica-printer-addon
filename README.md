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

It builds layer by layer from the bottom, flying over each layer in straight lanes while the printer places all the way. With "Build layers together" (on by default), a layer whose blocks stand on the layer below, like seeds on farmland or redstone dust on observers and pistons, is built together with that layer in the same lanes. Lanes stop at tall walls, such as the walls of a farm: it finishes one side lane by lane, then the other, and doesn't fly over parts that are already built. If you're kicked or the server restarts, it joins again, runs your "Commands back to the build" and carries on. The same happens when the server times out and the network moves you to its lobby: by default it types `/server Skyblock`, which puts you back where you were. Starting it again also retries the blocks it skipped last time.

Your inventory can start empty. It takes what it needs from your vaults and linked chests and buys the rest. Before a lane it tops up what the lane needs, and on the last shop trip of a layer it also buys for the next one. Anything that costs under $50,000 a stack is bought in whole stacks; what's left over goes into your vaults.

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
- It stops when you take damage or another player comes close.
- With the add-on off, Litematica Printer works as normal.
- A farm that's already running (pistons firing) doesn't hold up a layer. Pumpkins or melons that grew where a stem belongs are broken, so the stem can be planted.
- If the server won't let it craft a tool, it goes on without it. Once the server has refused two recipes put in by hand, it only crafts what's in your recipe book there, and remembers that between games.
- A container or unbreakable block of yours where the schematic wants something else (a hopper where redstone goes) doesn't hold up the layer. It's listed at the end for you to fix by hand.
- If nothing has been placed for 30 seconds while building, it plans its route again and leaves the stuck spot for later.
- Something wrong? Press `J`+`B`, click "Open build logs folder" and send the newest log.

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
