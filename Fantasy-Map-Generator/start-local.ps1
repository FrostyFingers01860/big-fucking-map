$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$nodeCandidates = @(
  (Join-Path $PSScriptRoot ".runtime\node.exe"),
  (Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe")
)

$systemNode = Get-Command node.exe -ErrorAction SilentlyContinue
if ($systemNode) {
  $nodeCandidates += $systemNode.Source
}

$node = $null
foreach ($candidate in $nodeCandidates | Select-Object -Unique) {
  if (-not (Test-Path -LiteralPath $candidate)) {
    continue
  }

  $version = (& $candidate --version).TrimStart("v")
  if ([int]($version.Split(".")[0]) -ge 24) {
    $node = $candidate
    break
  }
}

if (-not $node) {
  throw "Node.js 24 or newer is required. Install it from https://nodejs.org and run this launcher again."
}

$vite = Join-Path $PSScriptRoot "node_modules\vite\bin\vite.js"
$index = Join-Path $PSScriptRoot "dist\index.html"
if (-not (Test-Path -LiteralPath $vite) -or -not (Test-Path -LiteralPath $index)) {
  throw "The local build is incomplete. See LOCAL-README.md for repair instructions."
}

$url = "http://127.0.0.1:5173/Fantasy-Map-Generator/"
$server = Start-Process -FilePath $node -ArgumentList @(
  $vite,
  "preview",
  "--host", "127.0.0.1",
  "--port", "5173",
  "--strictPort"
) -WorkingDirectory $PSScriptRoot -NoNewWindow -PassThru

try {
  $ready = $false
  for ($attempt = 0; $attempt -lt 50; $attempt++) {
    if ($server.HasExited) {
      throw "The local map server stopped before it was ready. Port 5173 may already be in use."
    }

    try {
      $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 1
      if ($response.StatusCode -eq 200) {
        $ready = $true
        break
      }
    } catch {
      Start-Sleep -Milliseconds 200
    }
  }

  if (-not $ready) {
    throw "The local map server did not become ready."
  }

  Start-Process $url
  Write-Host ""
  Write-Host "Fantasy Map Generator is running locally at:"
  Write-Host $url -ForegroundColor Cyan
  Write-Host ""
  Read-Host "Press Enter to stop the local server"
} finally {
  if ($server -and -not $server.HasExited) {
    Stop-Process -Id $server.Id
  }
}

