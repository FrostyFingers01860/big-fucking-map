$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$bundleRoot = Split-Path -Parent $PSScriptRoot
$marker = Join-Path $bundleRoot ".big-fucking-map-root"
if (-not (Test-Path -LiteralPath $marker)) {
    throw "This setup must remain inside the downloaded Big Map bundle. The bundle marker is missing."
}

$runtime = Join-Path $bundleRoot ".runtime"
$downloads = Join-Path $runtime "downloads"
New-Item -ItemType Directory -Path $downloads -Force | Out-Null

function Get-VerifiedFile {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][string]$Sha256
    )
    if (Test-Path -LiteralPath $Destination) {
        $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
        if ($actual -ieq $Sha256) { return }
        Remove-Item -LiteralPath $Destination -Force
    }
    Write-Host "Downloading $([IO.Path]::GetFileName($Destination))..." -ForegroundColor Cyan
    Invoke-WebRequest -Uri $Uri -OutFile $Destination -UseBasicParsing
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash
    if ($actual -ine $Sha256) {
        Remove-Item -LiteralPath $Destination -Force
        throw "Checksum verification failed for $Uri"
    }
}

function Expand-SingleFolderArchive {
    param(
        [Parameter(Mandatory = $true)][string]$Archive,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    $temporary = "$Destination.extracting"
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
    New-Item -ItemType Directory -Path $temporary -Force | Out-Null
    Expand-Archive -LiteralPath $Archive -DestinationPath $temporary -Force
    $children = @(Get-ChildItem -LiteralPath $temporary -Force)
    if ($children.Count -eq 1 -and $children[0].PSIsContainer) {
        Move-Item -LiteralPath $children[0].FullName -Destination $Destination
        Remove-Item -LiteralPath $temporary -Force
    } else {
        Move-Item -LiteralPath $temporary -Destination $Destination
    }
}

Write-Host "Preparing portable Java 21..." -ForegroundColor Green
$jdkFolder = Join-Path $runtime "jdk-21"
$java = Join-Path $jdkFolder "bin\java.exe"
if (-not (Test-Path -LiteralPath $java)) {
    $adoptiumUri = "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&heap_size=normal&image_type=jdk&os=windows&vendor=eclipse"
    $assets = @(Invoke-RestMethod -Uri $adoptiumUri)
    if ($assets.Count -eq 0) { throw "Adoptium did not return a Java 21 Windows package." }
    $package = $assets[0].binary.package
    $jdkArchive = Join-Path $downloads $package.name
    Get-VerifiedFile -Uri $package.link -Destination $jdkArchive -Sha256 $package.checksum
    Expand-SingleFolderArchive -Archive $jdkArchive -Destination $jdkFolder
}

Write-Host "Preparing portable Node.js 24..." -ForegroundColor Green
$nodeFolder = Join-Path $runtime "node-24"
$node = Join-Path $nodeFolder "node.exe"
if (-not (Test-Path -LiteralPath $node)) {
    $releases = @(Invoke-RestMethod -Uri "https://nodejs.org/dist/index.json")
    $release = $releases | Where-Object { $_.version -match '^v24\.' -and $_.files -contains 'win-x64-zip' } | Select-Object -First 1
    if (-not $release) { throw "Node.js did not return a Windows x64 release for version 24." }
    $archiveName = "node-$($release.version)-win-x64.zip"
    $baseUri = "https://nodejs.org/dist/$($release.version)"
    $checksums = (Invoke-WebRequest -Uri "$baseUri/SHASUMS256.txt" -UseBasicParsing).Content
    $checksumLine = ($checksums -split "`n") | Where-Object { $_ -match "\s+$([regex]::Escape($archiveName))\s*$" } | Select-Object -First 1
    if (-not $checksumLine) { throw "The official Node.js checksum list did not contain $archiveName." }
    $nodeChecksum = ($checksumLine.Trim() -split '\s+')[0]
    $nodeArchive = Join-Path $downloads $archiveName
    Get-VerifiedFile -Uri "$baseUri/$archiveName" -Destination $nodeArchive -Sha256 $nodeChecksum
    Expand-SingleFolderArchive -Archive $nodeArchive -Destination $nodeFolder
}

$oldPath = $env:PATH
$oldJavaHome = $env:JAVA_HOME
$oldSkipHooks = $env:SKIP_INSTALL_SIMPLE_GIT_HOOKS
try {
    $env:JAVA_HOME = $jdkFolder
    $env:PATH = "$($jdkFolder)\bin;$nodeFolder;$oldPath"
    $env:SKIP_INSTALL_SIMPLE_GIT_HOOKS = "1"

    Write-Host "Building Districts..." -ForegroundColor Green
    & (Join-Path $bundleRoot "build.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Districts build failed with exit code $LASTEXITCODE." }

    Write-Host "Installing and building Fantasy Map Generator..." -ForegroundColor Green
    Push-Location (Join-Path $bundleRoot "Fantasy-Map-Generator")
    try {
        & (Join-Path $nodeFolder "npm.cmd") ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE." }
        & (Join-Path $nodeFolder "npm.cmd") run electron -- build
        if ($LASTEXITCODE -ne 0) { throw "Fantasy Map Generator build failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }

    $town = Join-Path $bundleRoot "TownGeneratorOS\App\TownGenerator.exe"
    if (-not (Test-Path -LiteralPath $town)) { throw "The bundled Town Generator executable is missing." }
} finally {
    $env:PATH = $oldPath
    $env:JAVA_HOME = $oldJavaHome
    $env:SKIP_INSTALL_SIMPLE_GIT_HOOKS = $oldSkipHooks
}

Write-Host ""
Write-Host "Setup complete." -ForegroundColor Green
Write-Host "Use the launchers in: $PSScriptRoot"
