# Bundled TownGeneratorOS

This directory is based on TownGeneratorOS commit:

`7fbc87a9398cc508af24de93f79cf2ad027f352b`

Upstream: https://github.com/watabou/TownGeneratorOS

The included source has local changes that:

- add a **Save JSON** button and complete city-model JSON export;
- reject a malformed wall-edge walk instead of looping forever;
- package the Neko build as a self-contained Windows GUI application.

`App/TownGenerator.exe` is the ready-to-run modified application. The exact corresponding source is present under `Source/`. The original GPL-3.0 license is retained as `LICENSE`.

Portable Haxe/OpenFL compiler caches are not committed. They are unnecessary for running the supplied build and can be recreated from the pinned versions in `project.xml` when developing the generator.
