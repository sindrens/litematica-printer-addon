# Printer Addon

Fabric add-on for [Litematica Printer](https://modrinth.com/mod/litematica-printer) on **Minecraft 26.2**.
Litematica Printer does the placing; the add-on builds whole schematics in survival around it:

- **One layer at a time** from the bottom, never skipping a block. Wrong blocks (wrong block, or facing the
  wrong way) are broken so the printer can place the right one.
- **Flies** above the layer, along the rows, to where the most blocks can be placed at once.
- **Automatic speed:** goes faster while every block comes out right (down to printer interval 2) and slows
  down at the first wrong one. Remembers the speed per server for the next build.
- **Gets the items:** takes them from chests, barrels and shulker boxes nearby (up to 500 blocks, in loaded
  chunks), then buys the rest in `/shop` (ShopGUI+), and crafts rails, powered rails and pumpkin seeds from
  iron and gold blocks, logs, redstone and pumpkins.
- Keeps the layer's blocks on the hotbar, eats when hungry, waits when the server lags, picks up again after
  logging out, and shows the build on screen (layer, blocks left, time to go, speed, what's missing).
- Safety: stops when you take damage or another player comes close, and never uses a nearly broken tool.

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
2. `J`+`B` starts auto build. It turns the printer on and builds from the bottom layer up. `J`+`B` again, or
   `J`+`Q`, stops it.
3. `J`+`C` (or Mod Menu) opens the settings.

| Keys | What |
|---|---|
| `J`+`B` | Start / stop auto build |
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
