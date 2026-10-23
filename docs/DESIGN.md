# Printer Addon – design

Fabric add-on for **Minecraft 26.2**, used next to Litematica Printer 3.2.2,
Litematica 0.28.8 and MaLiLib 0.29.6. Client only. License AGPL-3.0 (same as the printer).

Goal: build crop farms and other schematics perfectly in **survival**, with
`/fly` and items from the inventory and ShopGUI+ `/shop`.

## The build loop

The add-on builds **bottom-up, one Y layer at a time**, using Litematica's own
layer range (the printer only prints inside that range).

For each layer:

1. **Break wrong blocks** (while the printer is idle, so they never fight).
2. **Printer places blocks** (unchanged printer behaviour).
3. **Place scaffolding** where a block can't be placed (nothing to click on), place the
   real block against it, then remove the scaffolding.
4. **Move** to the next unfinished spot: walk, fly only when needed.
5. **Missing items** → buy what this layer needs from `/shop`, then continue.
6. Layer complete → next layer. Can't complete → pause and say in chat what blocks it
   and where.

## What counts as wrong

| Case | Action |
|---|---|
| Wrong block type | Break, printer places the right one |
| Right block, wrong direction/half/type (stairs, observers, logs, slabs) | Break and replace |
| Right block, state the printer can fix by clicking (repeater delay, comparator mode, etc.) | Click, don't break |
| Scaffolding the add-on placed itself | Removed once nothing pending needs it |
| Other blocks where the schematic has air (terrain, grass, stray blocks) | Only on its own key ("clear area"), never automatically |

Never broken: blocks outside the schematic placement, containers with items in them,
unbreakable blocks.

## Scaffolding

* Scaffold block: configurable, default **cobblestone**. It must not be used by the
  schematic itself, so the add-on can always tell scaffolding from real blocks.
* Every scaffold position is recorded and saved to a file per world/server, so a
  disconnect doesn't leave unknown blocks behind.
* Only blocks in that list are removed as scaffolding. Scaffolding is placed only where
  the schematic has air.

## Safe order for farms

Instead of placing whatever is closest, these blocks wait until their condition is met:

| Block | Waits until |
|---|---|
| Water / lava source | All neighbours (sides and below) are correct, so it can't flow onto redstone or crops |
| Farmland (tilled dirt) | Water within 4 blocks is placed; seeds / stem are planted right after tilling |
| Crops, stems, sugar cane, etc. | Farmland/support under it is correct |
| Sand / gravel / concrete powder | Block under it is correct |
| Observer | The block it watches is correct |
| Redstone dust, repeater, comparator, rails | Block under it is correct; placed after the solid blocks of the layer |
| Piston / sticky piston | Placed after the blocks that power it would affect |

## Movement

* **Built-in pathfinding** (no Baritone): walks on the ground and on finished layers,
  steps up 1 block, fly when there is no walking path or the target is out of reach
  from the ground. Sends `/fly` automatically if flying is needed and not on.
* Covers the layer in rows (like mowing a lawn) so no block is missed.
* Won't dig tunnels or bridge gaps (except scaffolding for placement).

## Shop (ShopGUI+, client only)

**Scan** (`J` + `S`): opens `/shop`, opens every category and sub-category, goes through every page
with the "next page" button and saves every item, its slot and its buy/sell price to
`config/printer_addon/shops/<server>.json`. It never clicks an item that shows a price, or a button
with "buy", "sell", "purchase" or "confirm" in its name. At the end it left-clicks the cheapest item
once to read the buy screen ("Buying → <item>" with +1 / +16 / +32 / more stacks), then closes it
without confirming.

**Buying** (later) uses that file:

* Material list from Litematica, compared with the inventory.
* Buys **only what the current layer needs**, rounded up to whole stacks.
* **Spending limit per build** (config). Over the limit: pause and ask in chat, confirm
  with a keybind.
* Opens `/shop`, goes to the right category and clicks buy (left-click only — never
  right-click, which sells in ShopGUI+). The item → shop/category/page mapping is
  generated from the server's ShopGUI+ files.
* Later: a server plugin with one command that charges money and gives the items.

## Inventory

* Breaking fills the inventory. When full: keep items the schematic needs and the
  scaffold block, drop the rest.

## Safety

* Stop on damage (alert in chat).
* Protect tools: never use a tool under a set durability (default 10).
* Auto eat when hunger is low.
* Stop when another player is within a set distance.

## Speed

Presets in the config: **Safe**, **Normal**, **Fast** (full speed, for servers
without anticheat) and **Custom** (every value editable: actions per tick, break
delay, move speed, shop click delay).

## Keybinds (defaults, all changeable)

Hold `J`, then press the second key. `J` isn't used by vanilla Minecraft.

| Action | Default |
|---|---|
| Open add-on settings | `J` + `C` |
| Start / stop auto build | `J` + `B` |
| Fix wrong blocks around me now | `J` + `F` |
| Clear extra blocks in the schematic area | `J` + `X` |
| Next / previous layer | `J` + `Page Up` / `Page Down` |
| Scan the shop | `J` + `S` |
| Stop everything (works in menus too) | `J` + `Q` |
| Buy missing items for this layer (later) | not set yet |

## Build order of the work

1. Project setup, settings screen, keybinds, speed presets.
2. Break wrong blocks (auto + key) with tool choice and tool protection.
3. Layer stepping.
4. Safe order for farms.
5. Scaffolding.
6. Movement (walk + fly).
7. Safety (damage, players, auto eat) and inventory handling.
8. Shop.
