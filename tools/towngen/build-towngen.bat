@echo off
rem Recompiles towngen.js (watabou's Medieval Fantasy City Generator, as a Node module) from the
rem TownGeneratorOS source, using that project's portable Haxe toolchain.
setlocal
set "TG=D:\Programs\TownGeneratorOS"
set "HAXEPATH=%TG%\.tools\haxe"
set "HAXE_STD_PATH=%TG%\.tools\haxe\std"
set "NEKOPATH="
set "PATH=%TG%\.tools\haxelib-bin;%HAXEPATH%;%TG%\.tools\neko32;%PATH%"
cd /d "%TG%"
haxe -cp Source -cp "%~dp0." -lib openfl -lib lime -lib msignal -main StationTownGen -js "%~dp0towngen.js" -dce full
if errorlevel 1 (echo Build failed. & exit /b 1)
echo Built %~dp0towngen.js
