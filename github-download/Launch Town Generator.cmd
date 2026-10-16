@echo off
setlocal
set "ROOT=%~dp0.."
set "TOWN=%ROOT%\TownGeneratorOS\App\TownGenerator.exe"
if not exist "%TOWN%" (
  echo The bundled Town Generator executable is missing.
  pause
  exit /b 1
)
start "Town Generator" "%TOWN%"
