# Test build library

Made by `tools/make_test_builds.py`. Each build is run with the farm game test: `tools/run_library.sh` runs them all
(or one: `tools/run_library.sh 03`) for both editions and writes the results to build/library-results.txt.

| File | What it tests |
|---|---|
| 01-stone-wall.litematic | Plain blocks, one layer at a time: the simplest build. (128 blocks) |
| 02-floating-floor.litematic | A floor with nothing under it: air place (Mythian), or placed off a first block. (400 blocks) |
| 03-small-house.litematic | Walls, a door, stairs facing out, slabs, glass panes, a torch: directions and half blocks. (259 blocks) |
| 04-redstone-line.litematic | Repeaters (delay and facing), comparators, wire, observers, a lamp: blocks that face a way. (108 blocks) |
| 05-hopper-chain.litematic | Hoppers pointing into each other and into a chest: sneak-clicks and placing order. (48 blocks) |
| 06-cactus-farm.litematic | Floating string, sand, cactus, layer on layer: the farm the add-on was made for. (640 blocks) |
| 07-sugar-cane.litematic | Water channels (bucket), sand, sugar cane beside the water. (180 blocks) |
| 08-melon-farm.litematic | Farmland (tilled with a hoe), stems, observers and pistons over the melons. (96 blocks) |
| 09-rail-line.litematic | Straight rails, a curve, powered rails on redstone blocks: rail shapes. (143 blocks) |
| 10-tall-tower.litematic | A hollow tower 48 high: tall builds, standing on what's built, the view of far layers. (768 blocks) |
| 11-big-flat.litematic | 200 x 200, two layers: speed of the layer checks (no stutter), long flights, lanes. (80000 blocks) |
| 12-logs-and-fences.litematic | Logs on all three axes, fences and walls that connect: shapes the printer must get right. (114 blocks) |
| 13-islands.litematic | Four floating islands apart: flights between parts, air place for each start. (688 blocks) |
| 14-crops.litematic | Wheat, carrots and potatoes on farmland around water: tilling and seeds. (199 blocks) |
