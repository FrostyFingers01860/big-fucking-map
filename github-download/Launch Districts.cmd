@echo off
setlocal
set "ROOT=%~dp0.."
set "JAVA=%ROOT%\.runtime\jdk-21\bin\javaw.exe"
set "JAR=%ROOT%\build\Districts.jar"
if not exist "%JAVA%" goto missing
if not exist "%JAR%" goto missing
pushd "%ROOT%"
start "Districts" "%JAVA%" -jar "%JAR%"
popd
exit /b 0
:missing
echo Districts is not built yet.
echo Run "Download Requirements and Build.cmd" first.
pause
exit /b 1
