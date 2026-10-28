# Printer Addon for server admins

Printer Addon (official build) moves, looks and clicks like a player. It also stays within vanilla reach and vanilla movement speed. It sends no server commands unless the player turns them on for your server.

## Turn it off on your server

Pick one of these:

- **MOTD tag:** put `[printer_addon:disable]` anywhere in your server's MOTD. You can hide it with color codes.
- **Plugin message:** send a message on the channel `printer_addon:policy` with the UTF-8 text `disable`.

When the add-on sees either one, auto build and Auto break stop and can't be started on your server. The player sees a message that says why.

## Allow faster movement

The add-on never moves faster than a player using the movement keys unless you allow it:

- **MOTD tag:** `[printer_addon:allow-fast-movement]`
- **Plugin message:** `allow-fast-movement` on `printer_addon:policy`

Send `default` on the same channel to go back to the normal rules.

## What it never does

- It never flies unless your server allows flying (for example with /fly or creative).
- It never reaches farther than vanilla.
- It never hides from mod lists. Its mod id is `printer_addon`.
- It never sends a command the player didn't set for your server.
