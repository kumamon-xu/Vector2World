[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$Port = 4173
)

$ErrorActionPreference = 'Stop'
$viewer = Join-Path (Split-Path -Parent $PSScriptRoot) 'spike-viewer'
Push-Location $viewer
try {
    if (-not (Test-Path -LiteralPath (Join-Path $viewer 'node_modules'))) {
        npm install
        if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit code $LASTEXITCODE" }
    }
    npm run dev -- --port $Port
    if ($LASTEXITCODE -ne 0) { throw "Vite failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
