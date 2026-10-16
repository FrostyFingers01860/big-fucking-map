$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$classesDirectory = Join-Path $projectRoot 'build\classes'
$jarPath = Join-Path $projectRoot 'build\Districts.jar'

New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$sources = Get-ChildItem -Path (Join-Path $projectRoot 'src\main\java') -Filter '*.java' -Recurse |
    ForEach-Object { $_.FullName }

if ($sources.Count -eq 0) {
    throw 'No Java source files were found.'
}

& javac -encoding UTF-8 -d $classesDirectory $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

Copy-Item -Path (Join-Path $projectRoot 'src\main\resources\*') -Destination $classesDirectory -Recurse -Force
& jar --create --file $jarPath --main-class districts.DistrictsApp -C $classesDirectory .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

Write-Host "Built $jarPath"
