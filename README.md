# Big Map bundle

This public repository keeps the complete working source and unique runtime data for three connected map tools in one folder:

- **Districts** — the Java district, region, transport, town and first-person world viewer.
- **Azgaar's Fantasy Map Generator** — the complete upstream source snapshot used to create the base world, with its Electron desktop application.
- **TownGeneratorOS** — the complete source snapshot plus the local JSON-export changes and a ready-to-run Windows build used for station towns.

It includes the current world, every historical world snapshot, terrain source data, all generated station-town layouts, the modified Town Generator Windows build, and the full source of all three projects. Only reinstallable dependency caches, generated terrain tile caches, duplicate local safety backups, build output, and personal first-person settings are excluded.

## Easiest Windows setup

1. Download this repository with **Code → Download ZIP** and extract it to its own folder.
2. Open `github-download`.
3. Double-click **Download Requirements and Build.cmd**.
4. Use the three launchers in that folder.

The setup downloads private, portable copies of Java 21 and Node.js 24 into `.runtime`; it does not require administrator access or replace system installations. It builds Districts and the desktop Fantasy Map Generator. Town Generator is already supplied as a self-contained Windows build.

See [github-download/README.md](github-download/README.md) for details and troubleshooting.

## Repository layout

| Folder | Contents |
| --- | --- |
| Repository root | Districts Java source, scripts, map/town data and [full documentation](DISTRICTS.md) |
| `Fantasy-Map-Generator/` | Azgaar Fantasy Map Generator source snapshot and local desktop helpers |
| `TownGeneratorOS/` | TownGeneratorOS source, local exporter changes and ready Windows runtime |
| `github-download/` | One-click setup, launchers and the guarded purge utility |
| `licenses/` | Copies of third-party licenses |

## Data included

Districts includes its persistent `world.bin`, earlier `world-v*.bin` snapshots, terrain cell data, terrain image and all 33,574 generated station-town layouts. The original high-resolution Fantasy Map Generator world is included as `data/source/Maptest1.json.zip`. Generated tile caches are omitted and rebuild automatically.

## Removing the bundle

`github-download/Purge.exe` permanently removes only the extracted bundle containing the repository marker. It has no folder picker, refuses unsafe/unmarked locations, requires a checkbox, and asks for confirmation again. Back up maps or other files saved inside the bundle before using it.
