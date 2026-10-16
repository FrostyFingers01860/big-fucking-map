# Districts

Districts is a dependency-free Java/Swing giant-map generator and viewer. It creates one persistent world containing an 8×8 district grid, branching region networks, regional highways, interconnected local roads, and many uniquely named stations. The included `all_town_names_unique.txt` is the supplied 699-name source file.

## Run on Windows

Requirements: Java 21 or newer (`java` and `javac` on `PATH`).

1. Double-click `run.bat`, or open PowerShell in this folder and run:

   ```powershell
   .\run.ps1
   ```

2. The script compiles the source, builds `build\Districts.jar`, and starts the viewer.

The packaged JAR can also be started directly after a build:

```powershell
java -jar build\Districts.jar
```

## Controls

- Hold **Ctrl** and use the mouse wheel to zoom at the cursor.
- Zoom can continue to ×1200, allowing every station name to be separated and read.
- Drag with the left or middle mouse button to pan.
- Use the wheel without Ctrl to pan vertically; hold Shift for horizontal pan.
- Double-click a region to zoom directly into its station layer. At station scale, double-click a city or town to select it and open its details in the right sidebar.
- Hover over districts, regions, or stations for details in the status bar.
- `Reset view` returns to the full 8×8 district overview.
- Open `Filters` to independently show or hide the **terrain map**, district labels, **rails**, regions, highways, **city roads**, **town roads**, **ferries**, cities, towns, place names, and the **ticket route**. The city-road layer is the connected road subtree used to reach cities; shared trunks remain visible. `Show all` restores every layer.

District labels dominate the overview. Region icons and gold highways fade in at medium scale. Green local roads, towns, cities, and their names fade in at the close scale. Every station is either a town or a city—there is no third generic station type. Gold stars identify capital cities, gold diamond markers identify capital regions, squares are cities, and circles are towns.

Cyan lines are rails between district capital cities, gold lines are regional highways, and green lines are local roads.

## 3D view

Press **3D** in the toolbar to see the map as terrain built from the source map's heightmap. Everything on the map — terrain colours, district borders, rails, highways, roads, ferries and the ticket route — is draped over the ground (lake and sea beds are drawn as sand, under the water); station and region icons and names stay upright on top and are hidden when a nearer hill blocks them. Zoom in on a town and its buildings and walls stand up as 3D objects; the flat town layouts are only drawn in 2D.

- **Left-drag** pans, **Ctrl + wheel** zooms toward the point under the cursor, **right-drag** sideways turns the view and up/down tilts it, from straight down to a level, horizontal look (90°). The camera never goes underground: over rising ground the whole view is lifted. `Reset view` also resets the tilt and turn.
- **Relief ×N** sets the height exaggeration: ×1 is true scale (the highest peaks are about 1.5 km); the default ×25 makes relief easy to read from far out. Houses always keep their true size, so lower it when walking the streets.
- Double-click and hover work as in 2D, using the exact point under the mouse.
- A nearly level camera keeps at least about **185 km of forward terrain** even at the deepest zoom, so distant mountains do not disappear when the camera is near the ground. The outer terrain rings become progressively coarser toward the horizon, preserving their silhouettes without spending close-up detail on faraway ground.
- There is **no distance fog or terrain haze**. In first person, progressively coarser outer terrain rings continue all the way to the map boundary, so climbing or flying higher cannot reveal an empty area beyond the mesh.

**Terrain.** The source cell heights are smoothed into a 1024 × 1024 elevation grid (`HeightField`), then roughened with 2D Perlin noise (nine octaves, wavelengths from about 100 km down to about 375 m). How rough depends on the biome under it (glaciers and tundra rugged, wetlands and grassland smooth, taiga and forests in between) and grows with altitude. Near coasts the exact land/water test keeps the shoreline where the map draws it, while a distance field lowers the land into a level beach shelf and then deepens the seabed gradually; at the default ×25 relief the coastal grade is capped at 6°, preventing vertical fjord-like cell walls. The ground is drawn as nested rings around the camera, each three times the size of the one inside it, so detail is fine nearby and far ground stays cheap; noise finer than a ring's cells is left out so it does not shimmer.

**Water** is one flat sheet at the water line, drawn wherever it is nearer than the ground: seas, lakes and rivers all fill the same way. How see-through it is depends on how deep it is: shallow rivers show their sandy bed, lakes and seas are deep blue, and at low angles the surface picks up the sky. **Rivers** are not cut into the ground: the land slopes down toward them at no more than 15° as seen at the default ×25 relief (`HeightField.MAX_BANK_DEGREES`; the grade is tan 15° / (0.001 × 25) ≈ 10.7 relief units per world unit), so in high ground the valley starts far from the water, and only crosses the water line where the river is. A river is exactly as wide as the stretch below the water line (about 730 m to 3.2 km; bigger source rivers are wider) and only a few metres deep. The 3D view draws no river lines or texture at all: rivers are only the real water. Seen from far away, channels are drawn at least as wide as the mesh cells so rivers stay continuous.

### First person

Press **First person** in the toolbar (it turns 3D on if needed) to walk the map from the middle of the view:

- **W A S D** (or the arrow keys) move, **Shift** runs (×2.5), **Space** jumps, the **mouse** looks around, **Esc** leaves (so does switching to another window). Press **F** to toggle fly mode; while flying, WASD follows the look direction, **Space** rises, **Ctrl** descends and Shift boosts speed. The mouse pointer is hidden and held in the middle of the view.
- The walker has gravity, stands on the ground (or on the water surface), steps up small ledges (45 cm) and follows slopes down. It collides with houses, town walls, towers and gatehouses, sliding along their walls; it can walk through a gate's arched passage, and land on roofs it jumps or falls onto.
- **⚙** next to the button opens the settings: **height** (metres; it also sets the walker's width), **walk speed** (m/s), **jump height** (m), **aim sensitivity** (degrees per pixel), **vertical field of view** (25°–110°), **fly mode**, and a logarithmic **fly-speed slider** from 5 m/s to 500 km/s. Changes apply at once and are saved in `data\first-person.properties`.
- Distances are real metres, but the ground keeps the 3D view's relief exaggeration, so hills are steeper than real ones; lower **Relief** for true-scale terrain.
- First-person terrain reaches every edge of the world. A sharper copy of the map covers the ground within about 250 m and is redrawn as you move. Place markers use distance detail levels to avoid horizon clutter: nearby towns appear first, cities remain visible farther away, and capitals remain visible farthest. A marker is never drawn on a sky pixel without rendered terrain beneath it.
- First person targets **100 FPS**. Its internal 3D resolution adjusts to the measured cost of the complete frame while the interface, labels, physics and world stay at full precision. The top-right counter shows actual FPS, complete frame time, 3D-render time and the current internal resolution percentage.

**Houses** come from each station's stored town layout (`TownMeshes`), built in the background for the nearest towns big enough to see (up to 24 at once) and cached:
- every building outline becomes a solid with walls and a roof; the floor is at the ground height of its highest corner, so nothing is buried on a slope, and a stone foundation runs from there to below its lowest corner, anchoring it to the ground; towns on a slope climb it house by house;
- storeys vary by ward: cathedrals 4–7, castles 3–5, merchant, administration and patriciate wards 2–4, military 2–3, slums and markets 1–2, farms 1, others 1–3;
- most four-sided houses get a gabled (triangle) roof running along their longer side; the rest, and every triangular house, get a flat roof;
- windows are a lightweight procedural pattern (rows per 3.2 m storey), drawn only when a wall is big enough on screen; each house gets one door, a plain rectangle on its longest wall whose outside is not blocked by another building;
- town walls are simple stone slabs with octagonal towers; each gate is a stone gatehouse with a round-arched passage through it, standing where the 2D layout draws the gate bar.

Only faces turned toward the camera are drawn. Zooming in reaches about 36 m from the target, close enough to walk the streets. First-person terrain samples, triangle buffers and raster work lists are reused between frames, nearby terrain samples move with the walker instead of being regenerated wholesale, and distant texture sampling uses a fast path. Terrain expands to the world boundary through cheap distance rings, while off-screen marker clusters and town meshes too distant to resolve are rejected before expensive height and projection work. A texture redraw remains asynchronous and is deliberately rate-limited while walking.

## Travel tickets

Open the **Ticket** tab in the right sidebar (or press `Ticket` in the toolbar). Type a station name in **From station** and **To station**; suggestions appear as you type and station names are unique. You can also double-click a station on the map and press **Set as start** or **Set as destination** in the Station tab.

The ticket shows the fastest journey as numbered steps. For each step it lists the vehicle, which direction to ride, where to board, the expected wait, how many stops to stay on, and where to get off. The DISTANCE box also shows the straight-line distance between the two stations and how much longer the route is.

- **Bus** — local roads. Each region has one bus route (`Bus <district>-<region>`), stopping at every station and ferry port.
- **Ferry** — where a road meets water. Each crossing is its own ferry between two ports (or a shore-side station that serves as its own dock).
- **Subway** — highways. Each district has one subway line connecting its region hubs (the station at each region's centre).
- **Train** — rail services between district capitals, including their intermediate stops. Changing between rail lines is a transfer.

Distances are scaled so that a straight bullet-train ride from one map corner to the opposite corner takes **58 hours** at 300 km/h, making one district about 1,538 km across. Buses run at 50 km/h, ferries at 30 km/h and subways at 100 km/h. Fastest time also includes average waits (bus 5, subway 3, train 8, ferry 15 min) and stop times. All of these live in `src\main\java\districts\travel\TicketPlanner.java`.

The issued route is drawn on the map (green bus, purple dashed ferry, gold subway, cyan train, with START/END and every transfer labelled) and the view zooms to fit it. In **Filters**, **Ticket route** shows or hides it and **Focus on ticket route** dims the rest of the map. **Expand ticket** opens the whole ticket full screen, laid out in columns so every step fits.

## Terrain

`data\terrain-base.png` is a 4096 x 4096 terrain picture of the `Maptest1.json` Fantasy Map Generator world (texture, relief, lakes, biomes, ice and rivers), spanning the full 8 x 8 district grid. `tools\render-fmg-terrain.cjs` reproduces it.

`data\terrain-cells.bin` holds the same world's terrain — each cell's outline, height, biome, water type, river flag and display colour, plus lake outlines, river paths and the texture seed — and nothing else. Generation uses it to tell land from water, and the viewer draws every terrain tile from it: every cell below height 20 is water (ocean or lake). Recreate it with:

```powershell
python tools\extract-terrain-cells.py "D:\Programs\Fantasy Map Generator\Maptest1.json" data\terrain-cells.bin
```

### Tiled display (full quality at every zoom)

The terrain is drawn like Google Maps, as 256 px tiles at 20 resolutions: from the whole map 256 px wide (level 0) up to over 130 million pixels wide (level 19), enough for the deepest zoom. Tiles are drawn straight from the source map's shapes in `terrain-cells.bin` (each cell's shaded biome colour, lake outlines, rivers and paper texture, using the same colours and shading as `tools
ender-fmg-terrain.html`), so they are equally sharp at every zoom.

Only the tiles on screen are made, newest first, on background threads, at the resolution that matches the zoom. While a tile is being made, the best lower-resolution tile already in memory is stretched in its place, so the map sharpens as tiles fill in. Levels 0–5 are also saved in `data	errain-tiles\` so later launches open instantly; that cache rebuilds itself whenever the terrain data changes. Shorelines and rivers keep the picture's widths when zoomed out but are capped at a few pixels when zoomed far in, so they stay lines.

`data	errain-base.png` is no longer used by the viewer; it is kept as the reference picture.

### Land, shores and ferries

- Region centres and every station stand on land. The capital city keeps at least ~90 km of land between it and any water (a water-heavy district uses its most inland point instead).
- Region spacing is stretched 10% so stations spread further across each district. District 19 is the most water-dominated district (about 56% land), so its regions sit more sparsely around its lake.
- A station that would land on water is moved to the nearest shore in its district and tagged `shoreSide=true`. Stations are never otherwise drawn toward water.
- **Site rule** (for the 3D terrain): every town's whole footprint keeps at least ~150 m clear of rivers, and no town stands on ground steeper than a 4% grade at true scale (45° at the default ×25 relief), so towns climb gentle slopes but never cling to valley walls. Constants: `TownSpacing.RIVER_MARGIN` and `TownSpacing.MAX_TOWN_SLOPE`. Stations that break it are moved to the nearest good site in their district (clear of every other town) and their region's roads are rebuilt; this runs automatically at start-up whenever the rule changes (the world stores the rule it satisfies as `siteRule`), and `--verify` checks it. The first run moved 141 stations (world before it: `data\world-v7-before-site-rule.bin`); widening the rivers moved 103 more (`data\world-v7-before-wide-rivers.bin`), and making them 4× wider with 15° valleys moved 217 more (`data\world-v7-before-15-degree-rivers.bin`).
- Every station records the terrain beneath it: `terrainBiome`, `terrainBiomeId`, `terrainHeight`, `terrainRiver`, `terrainCoastalCell` and `terrainDistanceToWater`.
- Roads never cross water. Road layout treats water as very expensive, so roads go around lakes when they reasonably can. When a crossing is unavoidable, the road ends at a **ferry port** on the shore, a **ferry** crosses to a docking port on the far side, and the road continues from there. Ports within ~6 km of each other are shared, so one port can serve several ferries; long crossings are only built when there is no alternative. A shore-side station a ferry lands at directly acts as its own port and is tagged `ferryDock=true`.
- Rails and subways (highways) may cross water.
- `Filters > Ferries` shows or hides ferries (dashed light blue) and ports (small squares).

## Station town layouts

Every one of the 33,574 stations has its own town plan from watabou's **Medieval Fantasy City Generator** (TownGeneratorOS): wards, every building, streets, roads, and — for walled places — walls, towers and gates. They are drawn on the map in the generator's colours — grey buildings with fine dark outlines, darker parks, dark walls with towers and gates; no ground fill and none of the generator's roads, so the terrain shows through the streets — once you zoom in far enough to see them, and only at the resolution the zoom needs: each town is pre-rendered in the background at the next power-of-two size above its on-screen size, cached, and drawn as vectors when very close. `Filters > Town layouts` shows or hides them. Zoom goes to ×120000 so you can walk the streets of any town and see individual buildings.

**Which layout each station gets** (decided by `TownPlan`, reproducibly from the world seed, and stored on each station as `townClass`, `townSize` and `townSeed`; shown in the Station tab):

- every **town** is a **Large Town** (generator size 10–14);
- every **capital** is a **Large City** (size 24–39);
- each city in **District 1** has an **80%** chance of being a Large City, otherwise a Small City (size 15–23);
- all other cities are exactly **20% Large Cities** and **80% Small Cities**.

That gives 28,296 large towns, 4,027 small cities and 1,251 large cities.

**One scale for every layout.** Every town and city uses the same scale — one generator unit is about 3.8 m — so buildings are the same size everywhere and cities are bigger only because they are bigger: a large town is roughly 250 m in radius, a large city roughly 500 m. Changing `TownScale.WORLD_PER_UNIT` to a smaller value rescales the stored footprints automatically on the next launch. Nothing is shrunk to fit its surroundings.

**Hard proximity cap.** No two stations may be closer than 1.15 × the sum of their two layouts' radii, across region and district borders too, so layouts never touch. Applying this moved 7,383 stations (22%) — capitals and region-centre stations stay put; any other station that was too close moved to the nearest valid spot on land inside its district — and the roads and ferries of the 656 affected regions were rebuilt. Each station stores its footprint as `townRadius`; moved stations are tagged `relocatedForTownSpacing`. The validator checks the cap on every launch. The world before this step is kept as `data\world-v7-before-town-spacing.bin`.

**Kept for later.** The stored layouts hold more than is drawn: every ward's type (`CraftsmenWard`, `MerchantWard`, `Market`, `Cathedral`, `Castle`, `MilitaryWard`, `PatriciateWard`, `Slum`, `Park`, `GateWard`, …), every building's full outline to 1/16 generator unit (about 24 cm), each ward's outline, the wall, towers and gates, and the generator's streets and roads. `StationTowns.load` returns all of it, ready for things like building heights by ward type or 3D parallax.

**Pregenerated data.** The layouts live in `data\towns\district-NN.bin` (one file per district, an index plus each station's zlib-compressed layout, about 139 MB in total). To regenerate them:

```powershell
java -jar build\Districts.jar --plan-towns
node tools\generate-station-towns.js data\towns\tasks.txt data\towns
```

The first command writes `data\towns\tasks.txt` (station, size, seed, class); the second runs the generator on every CPU core (about 20 seconds). The generator itself is `tools\towngen\towngen.js`, compiled from the TownGeneratorOS source by `tools\towngen\build-towngen.bat`. That source carries one fix: `Model.findCircumference` could loop forever on some seeds; it now raises the generator's usual "bad shape" error so it retries with another layout.

## One-time generation and saved data

The generated world is stored at `data\world.bin`. This project already includes an initially generated world. Every later launch loads that exact file, so district numbering, locations, and station names remain unchanged. Version-3 to version-5 saves are upgraded in place. A version-6 save (from before terrain) is backed up as `data\world-v6-before-terrain.bin` and re-laid out on the terrain the first time it is opened: every district, region and station keeps its number, name, type and capital status, so region and station counts never change; only positions, highways, roads, ferries and rails are rebuilt.

To intentionally make a completely different world, close the app, remove `data\world.bin`, and run again. The generator is retained in source so the save can be recreated or extended later.

Useful command-line checks:

```powershell
java -jar build\Districts.jar --verify
java -jar build\Districts.jar --generate-only --seed=12345 --data-dir=data-test
```

`--verify` loads the persistent world and checks its structural guarantees without opening the UI.

## Generation rules implemented

- District 1 starts in one of the four middle cells.
- Districts 2–64 repeatedly choose an unnumbered 8-neighbor frontier cell. Normal selection is random; 10% of selections choose among the cells farthest from District 1. Every district therefore touches a lower-numbered district, creating a strictly increasing path from District 1 to every cell.
- Region counts stay in the required 4–33 range. A quadratic distance falloff makes central districts strongly favor larger counts, with bounded random variation.
- Each district chooses one random-numbered region as its capital. Regions grow spatially outward from it. The **highway** network is a priority-weighted minimum spanning tree, favoring the capital region while remaining close to the absolute shortest possible network.
- Cyan **rails** connect the capital cities of all 64 districts. A sparse importance-weighted backbone guarantees access, while only the highest-value detours receive express links. District importance decreases with its number. District 1 receives four geographically dispersed far-reach lines and limited extra connectivity, making it the easiest rail origin without connecting it directly to every district. Each selected line is a through-service: up to two other capital cities become intermediate stops when they lie within 0.15 map units of its corridor and routing through them adds no more than 2.5% track distance. The service keeps its original origin and destination.
- Every region has 11–110 stations. Station clusters are concentrated mildly around their region hub and receive a subtle pull toward the district capital.
- Every station is connected into a green local-road network. A priority-weighted minimum spanning tree favors cities over towns and the capital city over ordinary cities. It reuses intermediate places instead of drawing redundant long bypasses.
- A measured `roadLocationStress` pass then considers geographically close station pairs. It builds a short additional road only when the current route is at least 2.35 times the construction distance, limits additions according to region size, and recalculates the benefit after every accepted road. These deliberate cycles remove extreme branch detours without turning the network into a mesh.
- Highways and the base road tree use Steiner-style geometric-median intersections when a shared junction shortens several branches. Generation records absolute MST length, priority cost, intersection savings, stress-relief construction cost, and travel distance saved in metadata.
- Each road segment records whether it belongs to the connected city-route subtree, town-route subtree, or both. The viewer exposes these as separate filters.
- Every station is classified as either a town or a city. Each capital region has exactly one capital station, and that station is always a city.
- Station names are globally unique, but naming styles are mixed independently inside every region. Untouched source names are spread across the world instead of being consumed in an early block. Each region combines unprefixed town-style derivatives, geographic suffix names, varied adjective names, occasional untouched source names, at most two literal `New <name>` variants, and occasional cardinal counterpart pairs. Directional variants remain paired inside the same region. No single adjective can repeat more than twice in a region, and the validator rejects any region dominated by one naming strategy. Explicit ` Lake` suffixes are tracked and limited to at most 5% of all stations.

## Extending the model

The serializable model is in `src\main\java\districts\model\World.java`. `World`, `District`, `Region`, `Station`, `Rail`, `Highway`, `Road`, and `Junction` each expose a string metadata map. Rails join district capital cities; highway and road segments connect named route nodes, including explicit intersections. Existing generation metadata includes roles, priorities, counts, distances, network stress, efficiency measurements, city/town route usage, name strategies, POI types, cardinal pair IDs, and Lake-suffix markers. New fields can be added to those maps without redesigning the renderer.

When making serialization-incompatible Java field changes, increment `World.FORMAT_VERSION` and intentionally regenerate `data\world.bin`.

## Project layout

```text
Districts/
├─ data/world.bin                         persistent generated world
├─ data/terrain-base.png                  terrain picture (cut into data/terrain-tiles/ on first launch)
├─ data/terrain-cells.bin                 terrain cells used for land/water and station terrain metadata
├─ src/main/resources/                    supplied town-name source
├─ src/main/java/districts/model/         metadata-ready data model
├─ src/main/java/districts/generator/     map, road, ferry, station, and name generation
├─ src/main/java/districts/terrain/       terrain cell lookups (land/water, shores, biomes)
├─ src/main/java/districts/travel/        fastest-route ticket planner
├─ src/main/java/districts/persistence/   one-time save/load support
├─ src/main/java/districts/ui/            Swing atlas renderer, tiles, tickets and controls
├─ tools/                                 terrain picture and terrain cell extractors
├─ build.ps1                              compiler/JAR build
├─ run.ps1
└─ run.bat
```
