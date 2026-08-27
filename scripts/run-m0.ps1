[CmdletBinding()]
param(
    [ValidateSet('geojson', 'shp')]
    [string]$Dataset = 'geojson',

    [string]$Output,

    [ValidateRange(1, 4)]
    [int]$Lod = 4,

    [ValidateRange(2, 1000)]
    [int]$MaxTiles = 2,

    [bool]$ClipToBounds = $false
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$wrapper = Join-Path $workspace 'mvnw.cmd'
$input = if ($Dataset -eq 'shp') {
    Join-Path $workspace 'test/shp/建筑面.shp'
} else {
    Join-Path $workspace 'test/geojson/建筑面.geojson'
}
if (-not $Output) {
    $Output = Join-Path $workspace "spike-viewer/public/generated/$Dataset"
}

if (-not (Test-Path -LiteralPath $input -PathType Leaf)) {
    throw "Input fixture was not found: $input"
}
if (Test-Path -LiteralPath $Output) {
    throw "Output must not already exist: $Output"
}

Push-Location $workspace
try {
    & $wrapper -pl building-tiler-backend -am -DskipTests "-Dmaven.javadoc.skip=true" install
    if ($LASTEXITCODE -ne 0) { throw "Maven install failed with exit code $LASTEXITCODE" }

    $execArgs = "--input `"$input`" --output `"$Output`" --height-field Elevation --zoom 15 --lod $Lod --max-tiles $MaxTiles --clip-to-bounds $($ClipToBounds.ToString().ToLowerInvariant())"
    & $wrapper -f building-tiler-backend/pom.xml exec:java "-Dexec.args=$execArgs"
    if ($LASTEXITCODE -ne 0) { throw "M0 generation failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
