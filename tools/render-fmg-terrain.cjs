const {app, BrowserWindow} = require("electron");
const path = require("path");

const inputPath = process.argv[2];
const outputPath = process.argv[3];
const outputSize = Number(process.argv[4] || 4096);

if (!inputPath || !outputPath) {
  console.error("Usage: electron render-fmg-terrain.cjs <Map.json> <terrain-base.png> [size]");
  process.exit(2);
}

app.whenReady().then(async () => {
  const window = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: false,
      nodeIntegration: true,
      sandbox: false
    }
  });

  await window.loadFile(path.join(__dirname, "render-fmg-terrain.html"));
  try {
    const result = await window.webContents.executeJavaScript(
      `renderTerrain(${JSON.stringify(inputPath)}, ${JSON.stringify(outputPath)}, ${outputSize})`,
      true
    );
    console.log(JSON.stringify(result));
    app.quit();
  } catch (error) {
    console.error(error?.stack || String(error));
    app.exit(1);
  }
});

