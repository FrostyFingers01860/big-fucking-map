"""Packages the lime Neko build as a self-contained Windows desktop app.

Usage:  python tools/make-desktop-app.py [Export/neko/bin] [.tools/neko32] [App]

lime builds TownGenerator.exe but does not copy the Neko virtual machine it runs on, and the exe
is marked as a console program (so Windows would open an empty console beside the window).
This script copies the build and the 32-bit Neko runtime into one folder and marks the exe as a
windowed (GUI) program, so the folder runs on any Windows PC by double-clicking the exe.
"""
import pathlib
import shutil
import struct
import sys

NEKO_RUNTIME = ["neko.dll", "gcmt-dll.dll", "std.ndll", "regexp.ndll", "zlib.ndll",
                "msvcp140.dll", "vcruntime140.dll"]
SUBSYSTEM_WINDOWS_GUI = 2


def mark_as_windowed(exe):
    """Sets the PE optional header's Subsystem field (offset 0x44 within it) to Windows GUI."""
    data = bytearray(exe.read_bytes())
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe_offset:pe_offset + 4] != b"PE\0\0":
        raise SystemExit(f"{exe} is not a Windows executable")
    subsystem_offset = pe_offset + 4 + 20 + 0x44
    struct.pack_into("<H", data, subsystem_offset, SUBSYSTEM_WINDOWS_GUI)
    exe.write_bytes(data)


def main(build_dir, neko_dir, output_dir):
    build = pathlib.Path(build_dir)
    neko = pathlib.Path(neko_dir)
    output = pathlib.Path(output_dir)
    if not (build / "TownGenerator.exe").is_file():
        raise SystemExit(f"No TownGenerator.exe in {build}; build the neko target first")
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(build, output)
    for name in NEKO_RUNTIME:
        shutil.copy2(neko / name, output / name)
    mark_as_windowed(output / "TownGenerator.exe")
    print(f"Wrote {output / 'TownGenerator.exe'} (windowed, with the Neko runtime beside it)")


if __name__ == "__main__":
    arguments = sys.argv[1:]
    main(arguments[0] if len(arguments) > 0 else "Export/neko/bin",
         arguments[1] if len(arguments) > 1 else ".tools/neko32",
         arguments[2] if len(arguments) > 2 else "App")
