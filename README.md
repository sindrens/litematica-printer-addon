# Printer Addon

Fabric add-on for [Litematica Printer](https://modrinth.com/mod/litematica-printer) on **Minecraft 26.2**.
Builds schematics, and crop farms in particular, in survival:

- **Breaks wrong blocks** (wrong block, or the right block facing the wrong way) so the printer can place
  the right one. Automatic during auto build, or on a key.
- **Builds one layer at a time** from the bottom and moves to the next layer when one is done.
- **Scans `/shop`** (ShopGUI+) and saves where every item is and what it costs.
- Safety: stops when you take damage or another player comes close, and never uses a nearly broken tool.

Coming next: safe build order for farms, temporary scaffolding, walking/flying, buying from the shop,
auto eat. See [docs/DESIGN.md](docs/DESIGN.md).

## Install

Put these in `.minecraft/mods`, together with this mod's jar:

| Mod | Version |
|---|---|
| Fabric Loader | 0.19.3 or newer |
| Fabric API | for 26.2 |
| MaLiLib | 0.29.6 |
| Litematica | 0.28.8 |
| Litematica Printer | 3.2.2 (26.2) |

## Use

1. Load and place a schematic in Litematica as usual.
2. Hold `J` and press `B` to start auto build. The printer turns on, Litematica switches to a single
   layer at the bottom, and wrong blocks within reach get broken. Walk around the layer for now; moving
   by itself comes later.
3. Hold `J` and press `C` for the settings (speed presets, break range, tool protection, safety...).

All keys: see [docs/DESIGN.md](docs/DESIGN.md#keybinds-defaults-all-changeable). They can be changed in
the settings' Hotkeys tab.

## Build

Needs Java 25.

```
./gradlew build
```

The jar ends up in `build/libs/`.

## License

AGPL-3.0, same as Litematica Printer.
