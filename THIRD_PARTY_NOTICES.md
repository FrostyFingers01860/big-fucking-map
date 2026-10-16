# Third-party notices

## Azgaar's Fantasy Map Generator

- Upstream: https://github.com/Azgaar/Fantasy-Map-Generator
- Snapshot commit: `403a670055b1a3f30373a64841eb2efe7b5e4688`
- License: MIT
- Copyright: Max Haniyeu (Azgaar) and contributors
- Included at: `Fantasy-Map-Generator/`

The full MIT license is retained in that component and copied to `licenses/FANTASY-MAP-GENERATOR-MIT.txt`.

## TownGeneratorOS / Medieval Fantasy City Generator

- Upstream: https://github.com/watabou/TownGeneratorOS
- Base snapshot commit: `7fbc87a9398cc508af24de93f79cf2ad027f352b`
- License: GNU General Public License version 3
- Copyright: original TownGeneratorOS contributors
- Included at: `TownGeneratorOS/`

This bundle includes local modifications for JSON export, safer layout generation and Windows desktop packaging. The complete corresponding modified source is included. The full GPL-3.0 license is retained in that component and copied to `licenses/TOWN-GENERATOR-GPL-3.0.txt`.

## Runtime dependencies

The setup script downloads Eclipse Temurin OpenJDK 21 and Node.js 24 from their official HTTPS distribution endpoints. Fantasy Map Generator's JavaScript packages retain their own notices in `package-lock.json` and their installed package directories. The Town Generator Windows runtime ships the Neko, Lime and Microsoft runtime files required by that GPL application.
