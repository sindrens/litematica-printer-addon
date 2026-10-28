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
- The Discord link is opt-in and only works over https.

## Requires
- Fabric API
- MaLiLib
- Litematica
- Litematica Printer

## License and source
AGPL-3.0. The source code is public (link to the repository).
