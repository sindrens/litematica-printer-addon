# Printer Addon

An add-on for **Litematica Printer** (Minecraft 26.2, Fabric). It builds a schematic layer by layer. It also fixes wrong blocks, moves you to the next unfinished blocks and gets the items it needs from your linked chests.

> **Warning: many servers don't allow printers or automation.** Read the server's rules before you use it. You can be banned, and that's on you. Server admins can turn it off for their server (see below).

## How it plays
- It moves with the normal movement keys, so you never go faster than a player.
- It only flies when the server lets you fly (for example with /fly or creative).
- Your camera really turns to the blocks it clicks. It sneaks with the real sneak key.
- It opens your inventory before it moves items, and you stand still while it's open.
- It never reaches farther than vanilla.
- You choose how fast it places and breaks blocks. If you pick something faster than a player, it warns you in chat.

## Server commands
Commands like /fly, /shop, /pv, /craft and /feed are **off** on every server until you turn them on. Use the "Commands (this server)" tab to do that. The settings are saved for each server.

## For server admins
- Put `[printer_addon:disable]` in your MOTD to turn the add-on off on your server.
- Or send `disable` on the plugin channel `printer_addon:policy`.
- Faster movement is only used if you allow it with `[printer_addon:allow-fast-movement]`.
- See docs/SERVER_ADMINS.md for the details.

## Privacy
Nothing is uploaded by default.
- You can send a run's log yourself after a run.
- Or you can turn on "Always send logs".
- The Discord link sends nothing until you link your account yourself (J+L).

## Requires
- Fabric API
- MaLiLib
- Litematica
- Litematica Printer

## License and source
AGPL-3.0. The source code is public (link to the repository).

## Also in this release
- **Safety stops:** it stops in lava, in the void, at low health, or when the inventory has been full a long time. It never breaks outside the build.
- **Why did it stop?** The Controls tab and /printeraddon why tell you why it stopped and what to do.
- **Basic and Advanced settings:** Basic shows only what most players need. Norwegian translation included.
- **Works with** Sodium and Iris. Mod Menu is optional.

# AutoSell (separate mod)
AutoSell sells skyblock collectors by itself: it goes to a full one, clicks Sell All, then goes back.
- It moves with the movement keys and only flies where the server lets you.
- It sends no /fly or /pay unless you set them.
- Server admins can turn it off with the MOTD tag `[autosell:disable]`, or with `disable` on the plugin channel `autosell:policy`.
