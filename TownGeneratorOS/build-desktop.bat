@echo off
rem Builds the desktop app into App\TownGenerator.exe (a normal Windows program, not a web page).
rem Uses the portable toolchain in .tools (Haxe 3.4.7, Neko 2.3.0) and the libraries in .haxelib.
setlocal
cd /d "%~dp0"
set "HAXEPATH=%~dp0.tools\haxe"
set "HAXE_STD_PATH=%~dp0.tools\haxe\std"
set "NEKOPATH="
rem haxelib.exe is 64-bit and finds its own Neko runtime beside it; lime's tools need the 32-bit Neko.
set "PATH=%~dp0.tools\haxelib-bin;%HAXEPATH%;%~dp0.tools\neko32;%PATH%"
haxelib run lime build windows -neko -release
if errorlevel 1 goto failed
python tools\make-desktop-app.py Export\neko\bin .tools\neko32 App
if errorlevel 1 goto failed
echo.
echo Done. Run App\TownGenerator.exe
exit /b 0
:failed
echo.
echo Build failed. See the messages above.
pause
exit /b 1
