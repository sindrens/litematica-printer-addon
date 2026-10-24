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

It builds layer by layer from the bottom. If you're kicked or the server restarts, it joins again, runs your "Commands back to the build" (for example `/server skyblock`, `/is go`) and carries on.

## Keys

| Key | What it does |
|---|---|
| `J`+`B` | Build menu: start/stop, add-on on/off, link chests, logs |
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
- Something wrong? Press `J`+`B`, click "Open build logs folder" and send the newest log.

AGPL-3.0, same as Litematica Printer.
