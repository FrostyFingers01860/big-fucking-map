# Windows download and launch folder

This folder is the non-technical entry point for a downloaded copy of the repository.

## First-time setup

Double-click **Download Requirements and Build.cmd**.

It will:

1. download a portable Eclipse Temurin Java 21 JDK from Adoptium;
2. download the latest official Node.js 24 Windows build and verify its published SHA-256 checksum;
3. build `build/Districts.jar`;
4. install Fantasy Map Generator's locked packages with `npm ci` and build its Electron desktop application;
5. verify that the supplied Town Generator desktop build is present.

Everything is stored inside the extracted repository. Administrator access and changes to the system `PATH` are not required. An internet connection is required for the initial setup. Subsequent launches work from the downloaded files, except for optional application features that themselves fetch online content.

## Launchers

- **Launch Districts.cmd** — starts the Java map viewer.
- **Launch Fantasy Map Generator.cmd** — opens the generator as an Electron desktop window, not a browser/localhost tab.
- **Launch Town Generator.cmd** — opens the modified TownGeneratorOS Windows application with JSON export.

If a launcher reports missing files, run the setup again.

## Purge.exe

`Purge.exe` permanently deletes the entire extracted bundle, including generated caches and any files saved inside it. Move important maps elsewhere first.

For safety, the tool:

- has no folder picker or command-line target in normal use;
- only accepts the parent bundle containing the exact `.big-fucking-map-root` marker;
- rejects drive roots and Windows/program directories;
- requires an explicit acknowledgement checkbox;
- shows the exact folder and asks for confirmation a second time.

Deleting a GitHub repository is separate: this utility only removes the local downloaded folder.
