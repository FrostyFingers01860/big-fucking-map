# Local Fantasy Map Generator

This is a separate local copy of Azgaar's Fantasy Map Generator 1.154.1. It does not share files with the Districts project.

## Start it

Double-click **Start Fantasy Map Generator.cmd**. Your browser will open the generator at:

`http://127.0.0.1:5173/Fantasy-Map-Generator/`

Keep the launcher window open while using the map. Press Enter in that window to stop the local server.

## Maps and offline use

Use the generator's **Save** button to download important `.map` files. Browser storage is convenient, but downloaded `.map` files are the safer long-term copy.

The application itself runs from this folder. Features that fetch third-party fonts, textures, or online services can still require internet access.

## Repair or rebuild

The project requires Node.js 24 or newer. From this folder, run:

```powershell
npm install
npm run build
```

Then use the launcher again. The unchanged upstream documentation is in `README.md` and `docs/`.

## Source

Official project: https://github.com/Azgaar/Fantasy-Map-Generator

License: MIT (`LICENSE`)
