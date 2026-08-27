[CmdletBinding()]
param(
    [string]$EvidenceRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) 'output/m0-evidence')
)

$ErrorActionPreference = 'Stop'

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes))
}

function Read-Glb {
    param([string]$Path)

    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 28 -or [Text.Encoding]::ASCII.GetString($bytes, 0, 4) -ne 'glTF') {
        throw "Not a GLB file: $Path"
    }

    $jsonLength = [BitConverter]::ToUInt32($bytes, 12)
    $json = [Text.Encoding]::UTF8.GetString($bytes, 20, $jsonLength).TrimEnd([char]0x20, [char]0) | ConvertFrom-Json
    $binStart = 28 + $jsonLength

    [pscustomobject]@{
        Bytes = $bytes
        Json = $json
        BinStart = $binStart
        JsonHash = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes(($json | ConvertTo-Json -Depth 100 -Compress)))
    }
}

function Get-FloatBits {
    param([byte[]]$Bytes, [int]$Offset)
    return '{0:X8}' -f [BitConverter]::ToUInt32($Bytes, $Offset)
}

function Get-OrderIndependentGeometryHash {
    param([string]$Path)

    $glb = Read-Glb $Path
    $primitive = $glb.Json.meshes[0].primitives[0]
    $indexAccessor = $glb.Json.accessors[$primitive.indices]
    if ($indexAccessor.componentType -ne 5123 -or $indexAccessor.type -ne 'SCALAR') {
        throw "M0 semantic hash currently expects UNSIGNED_SHORT indices: $Path"
    }

    $attributeNames = @('POSITION', 'NORMAL', 'COLOR_0')
    foreach ($name in $attributeNames) {
        $accessor = $glb.Json.accessors[$primitive.attributes.$name]
        if ($accessor.componentType -ne 5126 -or $accessor.type -ne 'VEC3') {
            throw "M0 semantic hash currently expects FLOAT VEC3 ${name}: $Path"
        }
    }

    $vertices = @{}
    $positionAccessor = $glb.Json.accessors[$primitive.attributes.POSITION]
    for ($vertex = 0; $vertex -lt $positionAccessor.count; $vertex++) {
        $parts = [Collections.Generic.List[string]]::new()
        foreach ($name in $attributeNames) {
            $accessor = $glb.Json.accessors[$primitive.attributes.$name]
            $view = $glb.Json.bufferViews[$accessor.bufferView]
            $viewOffset = if ($null -eq $view.byteOffset) { 0 } else { [int]$view.byteOffset }
            $accessorOffset = if ($null -eq $accessor.byteOffset) { 0 } else { [int]$accessor.byteOffset }
            $offset = $glb.BinStart + $viewOffset + $accessorOffset + ($vertex * 12)
            $parts.Add((Get-FloatBits $glb.Bytes $offset))
            $parts.Add((Get-FloatBits $glb.Bytes ($offset + 4)))
            $parts.Add((Get-FloatBits $glb.Bytes ($offset + 8)))
        }
        $vertices[$vertex] = $parts -join ':'
    }

    $indexView = $glb.Json.bufferViews[$indexAccessor.bufferView]
    $indexViewOffset = if ($null -eq $indexView.byteOffset) { 0 } else { [int]$indexView.byteOffset }
    $indexAccessorOffset = if ($null -eq $indexAccessor.byteOffset) { 0 } else { [int]$indexAccessor.byteOffset }
    $indexStart = $glb.BinStart + $indexViewOffset + $indexAccessorOffset
    $triangles = [Collections.Generic.List[string]]::new()
    for ($index = 0; $index -lt $indexAccessor.count; $index += 3) {
        $triangle = @(
            $vertices[[BitConverter]::ToUInt16($glb.Bytes, $indexStart + ($index * 2))],
            $vertices[[BitConverter]::ToUInt16($glb.Bytes, $indexStart + (($index + 1) * 2))],
            $vertices[[BitConverter]::ToUInt16($glb.Bytes, $indexStart + (($index + 2) * 2))]
        ) | Sort-Object
        $triangles.Add($triangle -join '|')
    }

    $canonical = ($triangles | Sort-Object) -join "`n"
    return Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes($canonical))
}

if (-not (Test-Path -LiteralPath $EvidenceRoot -PathType Container)) {
    throw "Evidence directory was not found: $EvidenceRoot"
}

$runs = Get-ChildItem -LiteralPath $EvidenceRoot -Directory | Sort-Object Name
$summary = foreach ($run in $runs) {
    $report = Get-Content -LiteralPath (Join-Path $run.FullName 'generation-report.json') -Raw | ConvertFrom-Json
    $manifest = Get-Content -LiteralPath (Join-Path $run.FullName 'manifest.json') -Raw | ConvertFrom-Json
    $glbFiles = Get-ChildItem -LiteralPath $run.FullName -Filter *.glb -File -Recurse
    [pscustomobject]@{
        Run = $run.Name
        LOD = $report.lods -join ','
        Clip = $manifest.clipToBounds
        Buildings = $report.modeledBuildings
        CrossTile = $report.crossTileBuildings
        Meshes = $report.meshCount
        GlbBytes = ($glbFiles | Measure-Object Length -Sum).Sum
        Extensions = $report.validation.extensionsUsed -join ','
    }
}

$summary | Format-Table -AutoSize

$repeatRuns = $runs | Where-Object Name -Like 'geojson-lod2-unclipped-*'
$hashes = foreach ($run in $repeatRuns) {
    Get-ChildItem -LiteralPath $run.FullName -Filter *.glb -File -Recurse | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{
            Run = $run.Name
            Tile = $_.BaseName
            ByteHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            SemanticGeometryHash = Get-OrderIndependentGeometryHash $_.FullName
        }
    }
}

$hashes | Format-Table -AutoSize

$semanticGroups = $hashes | Group-Object Tile, SemanticGeometryHash
if (($semanticGroups | Where-Object Count -ne $repeatRuns.Count).Count -ne 0) {
    throw 'Repeated LOD2 runs are not geometrically equivalent.'
}

Write-Host 'PASS: repeated LOD2 runs have identical order-independent geometry hashes.'
