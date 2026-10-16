"""Extracts the terrain Districts needs from an Azgaar Fantasy Map Generator JSON export.

Usage:  python tools/extract-terrain-cells.py <Map.json> data/terrain-cells.bin

Only geography is kept: each cell's polygon, height, biome, water type, river flag and final
display colour, the lake outlines, the river paths and the paper-texture seed. Cultures, states,
settlements, routes and every other record are ignored. Colours use exactly the same shading
rules as tools/render-fmg-terrain.html, so tiles drawn from this file at any zoom match it.
The output is a big-endian binary file read by districts.terrain.TerrainMap.
"""
import json
import math
import struct
import sys

LAKE_GROUPS = {"freshwater": 1, "salt": 2, "sinkhole": 3, "frozen": 4, "lava": 5, "dry": 6}
LAKE_COLORS = {"freshwater": "#4c91ad", "salt": "#5596a9", "sinkhole": "#397f9b",
               "frozen": "#c9e9ef", "lava": "#b94a2e", "dry": "#9a8a65"}
GLACIER_BIOME = 11


def js_round(value):
    """JavaScript Math.round: halves round toward +infinity."""
    return math.floor(value + 0.5)


def hex_rgb(color):
    value = int(color.replace("#", ""), 16)
    return (value >> 16) & 255, (value >> 8) & 255, value & 255


def shade(color, amount):
    return tuple(max(0, min(255, channel + amount)) for channel in hex_rgb(color))


def cell_color(cell, biomes, cells):
    biome = biomes[cell["biome"]] if 0 <= cell.get("biome", -1) < len(biomes) else None
    base = biome.get("color") if biome else ("#9baa75" if cell["h"] >= 20 else "#466eab")
    if cell["h"] < 20:
        amount = js_round((cell["h"] - 14) * 1.6)
    else:
        slope_light = 0.0
        for neighbor_id in cell.get("c") or []:
            if not 0 <= neighbor_id < len(cells):
                continue
            neighbor = cells[neighbor_id]
            dx = neighbor["p"][0] - cell["p"][0]
            dy = neighbor["p"][1] - cell["p"][1]
            distance2 = dx * dx + dy * dy or 1
            slope_light += (neighbor["h"] - cell["h"]) * (dx + dy) / distance2
        amount = js_round(max(-18, min(20, (cell["h"] - 42) * 0.18 - slope_light * 3.8)))
    r, g, b = shade(base, amount)
    if cell.get("biome") == GLACIER_BIOME:
        # The renderer lays rgba(225,245,248,.42) over glacier cells; bake it into the colour.
        r, g, b = (round(r * 0.58 + 225 * 0.42), round(g * 0.58 + 245 * 0.42), round(b * 0.58 + 248 * 0.42))
    return r, g, b


def main(source_path, output_path):
    with open(source_path, encoding="utf-8") as handle:
        source = json.load(handle)
    width = float(source["info"]["width"])
    height = float(source["info"]["height"])
    seed = int(source["info"]["seed"]) & 0xFFFFFFFF
    pack = source["pack"]
    vertices = pack["vertices"]
    features = pack["features"]
    biomes = pack["biomes"]
    cells = pack["cells"]
    if any(cells[index]["i"] != index for index in range(len(cells))):
        raise SystemExit("Expected cells to be stored in id order")

    with open(output_path, "wb") as out:
        out.write(b"DTRC")
        out.write(struct.pack(">ifff", 2, width, height, 0.0))
        out.write(struct.pack(">i", len(biomes)))
        for biome in biomes:
            name = biome.get("name", "Unknown").encode("utf-8")
            out.write(struct.pack(">H", len(name)))
            out.write(name)
        out.write(struct.pack(">i", len(cells)))
        water_cells = 0
        for cell in cells:
            polygon = [vertices[index]["p"] for index in cell["v"] if index < len(vertices)]
            h = int(cell["h"])
            feature = features[cell["f"]] if 0 <= cell.get("f", 0) < len(features) else None
            feature_type = feature.get("type") if feature else None
            # FMG marks every water cell (ocean and lakes) with a height below 20.
            if h < 20:
                water = 1 if feature_type == "ocean" else 2
                water_cells += 1
            else:
                water = 0
            lake_group = LAKE_GROUPS.get(feature.get("group"), 1) if water == 2 and feature else 0
            flags = (1 if cell.get("r", 0) else 0) | (2 if cell.get("t", 0) in (1, -1) else 0)
            r, g, b = cell_color(cell, biomes, cells)
            out.write(struct.pack(">BBBBBH", min(255, max(0, h)), int(cell.get("biome", 0)),
                                  water, lake_group, flags, len(polygon)))
            for x, y in polygon:
                out.write(struct.pack(">ff", float(x), float(y)))
            out.write(struct.pack(">BBB", r, g, b))

        # ---- version 2 display layers ----
        out.write(struct.pack(">I", seed))
        out.write(struct.pack(">BBB", *hex_rgb(biomes[0].get("color", "#466eab") if biomes else "#466eab")))
        lakes = [feature for feature in features
                 if feature and feature.get("type") == "lake" and feature.get("vertices")]
        out.write(struct.pack(">i", len(lakes)))
        for lake in lakes:
            points = [vertices[index]["p"] for index in lake["vertices"] if index < len(vertices)]
            color = LAKE_COLORS.get(lake.get("group") or lake.get("subtype"), LAKE_COLORS["freshwater"])
            out.write(struct.pack(">BBBi", *hex_rgb(color), len(points)))
            for x, y in points:
                out.write(struct.pack(">ff", float(x), float(y)))
        rivers = pack.get("rivers") or []
        written = []
        for river in rivers:
            points = [cells[index]["p"] for index in (river.get("cells") or []) if 0 <= index < len(cells)]
            if len(points) >= 2:
                width_units = max(0.42, min(3.6, math.sqrt(river.get("discharge") or 30) * 0.055))
                written.append((width_units, points))
        out.write(struct.pack(">i", len(written)))
        for width_units, points in written:
            out.write(struct.pack(">fi", width_units, len(points)))
            for x, y in points:
                out.write(struct.pack(">ff", float(x), float(y)))
    print(f"Wrote {output_path}: {len(cells)} cells ({water_cells} water), {len(lakes)} lakes, "
          f"{len(written)} rivers, {width:.0f}x{height:.0f} source units")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
