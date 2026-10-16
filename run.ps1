$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

& (Join-Path $PSScriptRoot 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& java -jar (Join-Path $PSScriptRoot 'build\Districts.jar')
