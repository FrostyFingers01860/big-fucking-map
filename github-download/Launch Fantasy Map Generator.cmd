@echo off
setlocal
set "ROOT=%~dp0.."
set "SERVICE=%ROOT%\Fantasy-Map-Generator"
set "ELECTRON=%SERVICE%\node_modules\electron\dist\electron.exe"
if not exist "%ELECTRON%" goto missing
if not exist "%SERVICE%\dist-electron\main.js" goto missing
pushd "%SERVICE%"
start "Fantasy Map Generator" "%ELECTRON%" .
popd
exit /b 0
:missing
echo Fantasy Map Generator is not built yet.
echo Run "Download Requirements and Build.cmd" first.
pause
exit /b 1
