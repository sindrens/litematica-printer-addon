#!/usr/bin/env python3
"""Writes the test build library (testdata/library/*.litematic): small schematics that each cover one kind of build
the add-on has to handle. Run: python3 tools/make_test_builds.py   (needs: pip install nbtlib)

Each one is run with the farm game test, see tools/run_library.sh and testdata/library/README.md.
"""
import math
import os
import time

import nbtlib
from nbtlib import Compound, Int, List, Long, LongArray, String

OUT = os.path.join(os.path.dirname(__file__), "..", "testdata", "library")
DATA_VERSION = 4903  # Minecraft 26.2


class Build:
    def __init__(self, name, sx, sy, sz, description):
        self.name, self.sx, self.sy, self.sz, self.description = name, sx, sy, sz, description
        self.blocks = {}

    def set(self, x, y, z, block, **props):
        if 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz:
            self.blocks[(x, y, z)] = (block, tuple(sorted((k, str(v).lower()) for k, v in props.items())))

    def fill(self, x1, y1, z1, x2, y2, z2, block, **props):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for y in range(min(y1, y2), max(y1, y2) + 1):
                for z in range(min(z1, z2), max(z1, z2) + 1):
                    self.set(x, y, z, block, **props)

    def save(self, file):
        palette = [("minecraft:air", ())]
        index = {palette[0]: 0}
        for state in self.blocks.values():
            if state not in index:
                index[state] = len(palette)
                palette.append(state)
        bits = max(2, math.ceil(math.log2(len(palette))))
        volume = self.sx * self.sy * self.sz
        longs = [0] * ((volume * bits + 63) // 64)
        for (x, y, z), state in self.blocks.items():
            i = (y * self.sz + z) * self.sx + x
            value = index[state]
            start = i * bits
            li, offset = start // 64, start % 64
            longs[li] |= (value << offset) & 0xFFFFFFFFFFFFFFFF
            if offset + bits > 64:
                longs[li + 1] |= value >> (64 - offset)
        signed = [v - (1 << 64) if v >= (1 << 63) else v for v in longs]
        pal = List[Compound]()
        for name, props in palette:
            entry = Compound({"Name": String(name)})
            if props:
                entry["Properties"] = Compound({k: String(v) for k, v in props})
            pal.append(entry)
        now = int(time.time() * 1000)
        size = Compound({"x": Int(self.sx), "y": Int(self.sy), "z": Int(self.sz)})
        root = Compound({
            "MinecraftDataVersion": Int(DATA_VERSION),
            "Version": Int(7),
            "SubVersion": Int(1),
            "Metadata": Compound({
                "Name": String(self.name), "Author": String("Printer Addon tests"), "Description": String(self.description),
                "RegionCount": Int(1), "TotalVolume": Int(volume), "TotalBlocks": Int(len(self.blocks)),
                "TimeCreated": Long(now), "TimeModified": Long(now), "EnclosingSize": size,
            }),
            "Regions": Compound({
                "Main": Compound({
                    "BlockStatePalette": pal,
                    "BlockStates": LongArray(signed),
                    "TileEntities": List[Compound](),
                    "Entities": List[Compound](),
                    "PendingBlockTicks": List[Compound](),
                    "PendingFluidTicks": List[Compound](),
                    "Position": Compound({"x": Int(0), "y": Int(0), "z": Int(0)}),
                    "Size": size,
                }),
            }),
        })
        nbtlib.File(root, gzipped=True).save(file)


def wall():
    b = Build("01 Stone wall", 16, 8, 1, "Plain blocks, one layer at a time: the simplest build.")
    b.fill(0, 0, 0, 15, 7, 0, "minecraft:stone_bricks")
    return b


def floating_floor():
    b = Build("02 Floating floor", 20, 1, 20, "A floor with nothing under it: air place (Mythian), or placed off a first block.")
    b.fill(0, 0, 0, 19, 0, 19, "minecraft:white_concrete")
    return b


def small_house():
    b = Build("03 Small house", 9, 7, 9, "Walls, a door, stairs facing out, slabs, glass panes, a torch: directions and half blocks.")
    b.fill(0, 0, 0, 8, 0, 8, "minecraft:oak_planks")
    for y in range(1, 4):
        for i in range(9):
            for x, z in ((i, 0), (i, 8), (0, i), (8, i)):
                b.set(x, y, z, "minecraft:cobblestone" if i in (0, 8) else "minecraft:oak_planks")
    b.set(4, 1, 0, "minecraft:oak_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.set(4, 2, 0, "minecraft:oak_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    for x in (2, 6):
        b.set(x, 2, 0, "minecraft:glass_pane", east="true", west="true", north="false", south="false", waterlogged="false")
        b.set(x, 2, 8, "minecraft:glass_pane", east="true", west="true", north="false", south="false", waterlogged="false")
    for x in range(9):
        b.set(x, 4, 0, "minecraft:oak_stairs", facing="south", half="bottom", shape="straight", waterlogged="false")
        b.set(x, 4, 8, "minecraft:oak_stairs", facing="north", half="bottom", shape="straight", waterlogged="false")
    b.fill(0, 4, 1, 8, 4, 7, "minecraft:oak_slab", type="bottom", waterlogged="false")
    b.set(1, 2, 1, "minecraft:wall_torch", facing="south")  # hangs on the wall plank north of it
    return b


def redstone():
    b = Build("04 Redstone line", 16, 2, 5, "Repeaters (delay and facing), comparators, wire, observers, a lamp: blocks that face a way.")
    b.fill(0, 0, 0, 15, 0, 4, "minecraft:smooth_stone")
    for x in range(16):
        if x % 4 == 0:
            b.set(x, 1, 2, "minecraft:repeater", facing="west", delay=str(1 + x // 4 % 4), locked="false", powered="false")
        else:
            b.set(x, 1, 2, "minecraft:redstone_wire", east="side", west="side", north="none", south="none", power="0")
    for x in range(0, 16, 3):
        b.set(x, 1, 0, "minecraft:observer", facing="up", powered="false")
        b.set(x, 1, 4, "minecraft:comparator", facing="north", mode="subtract" if x % 2 else "compare", powered="false")
    b.set(15, 1, 0, "minecraft:redstone_lamp", lit="false")
    return b


def hoppers():
    b = Build("05 Hopper chain", 12, 2, 3, "Hoppers pointing into each other and into a chest: sneak-clicks and placing order.")
    b.fill(0, 0, 0, 11, 0, 2, "minecraft:stone")
    for x in range(11):
        b.set(x, 1, 1, "minecraft:hopper", facing="east", enabled="true")
    b.set(11, 1, 1, "minecraft:chest", facing="west", type="single", waterlogged="false")
    return b


def cactus_farm():
    b = Build("06 Cactus farm", 16, 10, 16, "Floating string, sand, cactus, layer on layer: the farm the add-on was made for.")
    for layer in range(3):
        y = layer * 3
        for x in range(0, 16, 2):
            for z in range(0, 16, 2):
                # The string holds the sand (first layer on wool); the next layer's string is over the cactus
                b.set(x, y, z, "minecraft:string" if layer else "minecraft:white_wool")
                b.set(x, y + 1, z, "minecraft:sand")
                b.set(x, y + 2, z, "minecraft:cactus", age="0")
    for x in range(0, 16, 2):
        for z in range(0, 16, 2):
            b.set(x, 9, z, "minecraft:string")
    return b


def sugar_cane():
    b = Build("07 Sugar cane", 15, 3, 7, "Water channels (bucket), sand, sugar cane beside the water.")
    b.fill(0, 0, 0, 14, 0, 6, "minecraft:stone")
    for x in range(15):
        b.set(x, 1, 3, "minecraft:water", level="0")
        for z in (2, 4):
            b.set(x, 1, z, "minecraft:sand")
            b.set(x, 2, z, "minecraft:sugar_cane", age="0")
    return b


def melon_farm():
    b = Build("08 Melon farm", 12, 4, 5, "Farmland (tilled with a hoe), stems, observers and pistons over the melons.")
    b.fill(0, 0, 0, 11, 0, 4, "minecraft:dirt")
    for x in range(12):
        b.set(x, 0, 1, "minecraft:farmland", moisture="7")
        b.set(x, 1, 1, "minecraft:melon_stem", age="0")
        b.set(x, 0, 2, "minecraft:water", level="0")
        b.set(x, 3, 3, "minecraft:observer", facing="up", powered="false")
        b.set(x, 2, 3, "minecraft:sticky_piston", facing="down", extended="false")
    return b


def rails():
    b = Build("09 Rail line", 16, 2, 8, "Straight rails, a curve, powered rails on redstone blocks: rail shapes.")
    b.fill(0, 0, 0, 15, 0, 7, "minecraft:stone")
    for x in range(8):
        b.set(x, 1, 1, "minecraft:rail", shape="east_west", waterlogged="false")
    b.set(8, 1, 1, "minecraft:rail", shape="south_west", waterlogged="false")
    for z in range(2, 8):
        b.set(8, 0, z, "minecraft:redstone_block")
        b.set(8, 1, z, "minecraft:powered_rail", shape="north_south", powered="true", waterlogged="false")
    return b


def tower():
    b = Build("10 Tall tower", 5, 48, 5, "A hollow tower 48 high: tall builds, standing on what's built, the view of far layers.")
    for y in range(48):
        for i in range(5):
            for x, z in ((i, 0), (i, 4), (0, i), (4, i)):
                b.set(x, y, z, "minecraft:polished_andesite" if y % 8 else "minecraft:spruce_planks")
    return b


def big_flat():
    b = Build("11 Big flat", 200, 2, 200, "200 x 200, two layers: speed of the layer checks (no stutter), long flights, lanes.")
    b.fill(0, 0, 0, 199, 0, 199, "minecraft:dirt")
    b.fill(0, 1, 0, 199, 1, 199, "minecraft:moss_carpet")
    return b


def orientation():
    b = Build("12 Logs and fences", 12, 4, 6, "Logs on all three axes, fences and walls that connect: shapes the printer must get right.")
    b.fill(0, 0, 0, 11, 0, 5, "minecraft:stone")
    for x in range(12):
        b.set(x, 1, 0, "minecraft:oak_log", axis="x")
    for z in range(1, 6):
        b.set(0, 1, z, "minecraft:oak_log", axis="z")
        b.set(11, 1, z, "minecraft:oak_log", axis="y")
    for x in range(1, 11):
        b.set(x, 1, 3, "minecraft:oak_fence", east="true", west="true", north="false", south="false", waterlogged="false")
        b.set(x, 1, 5, "minecraft:cobblestone_wall", east="low", west="low", north="none", south="none", up="false", waterlogged="false")
    return b


def islands():
    b = Build("13 Islands", 30, 3, 30, "Four floating islands apart: flights between parts, air place for each start.")
    for ox, oz in ((0, 0), (20, 0), (0, 20), (20, 20)):
        b.fill(ox, 0, oz, ox + 9, 0, oz + 9, "minecraft:grass_block", snowy="false")
        b.fill(ox + 2, 1, oz + 2, ox + 7, 2, oz + 7, "minecraft:oak_leaves", distance="1", persistent="true", waterlogged="false")
    return b


def crops():
    b = Build("14 Crops", 10, 2, 10, "Wheat, carrots and potatoes on farmland around water: tilling and seeds.")
    b.fill(0, 0, 0, 9, 0, 9, "minecraft:farmland", moisture="7")
    b.set(4, 0, 4, "minecraft:water", level="0")
    for x in range(10):
        for z in range(10):
            if (x, z) != (4, 4):
                b.set(x, 1, z, ("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes")[x % 3], age="0")
    return b


BUILDS = [wall, floating_floor, small_house, redstone, hoppers, cactus_farm, sugar_cane, melon_farm, rails, tower, big_flat,
          orientation, islands, crops]


def main():
    os.makedirs(OUT, exist_ok=True)
    lines = ["# Test build library", "",
             "Made by `tools/make_test_builds.py`. Each build is run with the farm game test: `tools/run_library.sh` runs them all",
             "(or one: `tools/run_library.sh 03`) for both editions and writes the results to build/library-results.txt.", "",
             "| File | What it tests |", "|---|---|"]
    for make in BUILDS:
        b = make()
        file = b.name.lower().replace(" ", "-") + ".litematic"
        b.save(os.path.join(OUT, file))
        lines.append(f"| {file} | {b.description} ({len(b.blocks)} blocks) |")
        print(f"{file}: {len(b.blocks)} blocks")
    with open(os.path.join(OUT, "README.md"), "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
