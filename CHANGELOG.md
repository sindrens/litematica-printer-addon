# Changelog

## 26.2-0.14.1
- Gets back to the build after "Timed out" and the network's lobby (types `/server Skyblock`).
- Plans the lanes again on every start.
- Spare tools make room on the hotbar. The hoe stays while there's tilling.
- A lane never waits more than 2 s with nothing placed.
- Blocks the printer won't take are left out for 30 s instead of waited on forever.
- Lanes skip the spots where a wall stands on their line.
- Whole-stack purchases always fit in the inventory.

## 26.2-0.14.2
- Fixed a lane that stood still for good on a redstone layer.
- The player no longer sinks along a lane or drifts into walls.
- Building two layers at once is on again.
- Lane parts inside a walled middle are reached over the wall.
- Flights over buildings no longer bump into them.
- Stops crafting tools the server refuses. A pickaxe and shovel take one block of diamond.
- Your own container in the way no longer holds up a layer.

## 26.2-0.14.3
- Floating string (the bottom of a cactus farm) is placed in the air.
- Air place stops if the server keeps none of the first 12 blocks.
- Cactus farms are built two layers at a time.
- Brushing a cactus doesn't stop the build while health is above half.
- The lowest printer interval is 3.
- Never flies past the world border (the island's edge).

## 26.2-0.14.4
- Interval 2 again, with a speed guard and a SERVER line every 10 s.
- Bulk buying into the vaults.
- Air place only for blocks that really float.
- Sand waits until the server has the string under it.
- Your own netherite or diamond tools count. No needless crafting.
- New setting "Always break these blocks" (cactus_flower by default).
- Conflicts are summed up instead of thousands of lines.
- Lanes start at the nearest edge.
- Fewer item switches. A second stack of the main items on the hotbar.

## 26.2-0.14.5
- Never left standing in a cactus: it flies up and out before stopping.
- Keeps clear of cactus while flying and rises over grown cactus.
- Speed guard: no more false 5 to 15 s stalls. Full speed again as soon as the server answers normally.
- Lane speed isn't learned while the guard slows the printer.
- Lanes are flown in order.
- Bulk buying: one big purchase per item when the vaults are out of it. No long round at the start. 15% of the vault slots always stay free.
- Remembers what's in each vault between sessions.
- Checks that vault clicks went through.
- Cactus flowers are never taken for your own blocks.
- Clicks only as far as clicks work on the server.
- Full run log: a SEC line every second, time in seconds by category, lane, layer, trip and supply summaries, click details with likely causes, a final report compared with the last run, a .jsonl file, repeated lines merged.

## 26.2-0.14.6
- Grown cactus (with flowers) aren't broken by hand any more. They drop when the sand beside them goes in.
- Cactus are always built together with the sand above them, so they can't grow while they wait.
