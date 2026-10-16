@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-All.ps1"
if errorlevel 1 (
  echo.
  echo Setup did not finish. Read the error above, then run this file again.
  pause
  exit /b 1
)
echo.
echo Everything is ready. Use the three Launch files in this folder.
pause
