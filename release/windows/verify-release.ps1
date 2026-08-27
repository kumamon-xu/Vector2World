[CmdletBinding()]
param([Parameter(Mandatory)][string]$ReleaseRoot)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ReleaseRoot).Path
$metadataPath = Join-Path $root 'release-metadata.json'
$sumsPath = Join-Path $root 'SHA256SUMS.txt'
if (-not (Test-Path -LiteralPath $metadataPath) -or -not (Test-Path -LiteralPath $sumsPath)) {
    throw 'Release metadata or checksum manifest is missing'
}
$metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
foreach ($name in @('version','buildNumber','gitSha','buildTime','osm2worldCommit','ruleVersion','presetVersion','signingStatus')) {
    if (-not $metadata.$name) { throw "Release metadata field is missing: $name" }
}
foreach ($line in Get-Content -LiteralPath $sumsPath) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Invalid checksum line: $line" }
    $file = Join-Path $root ($Matches[2] -replace '/', '\')
    if (-not (Test-Path -LiteralPath $file)) { throw "Checksummed artifact is missing: $file" }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant() -ne $Matches[1]) {
        throw "Checksum mismatch: $file"
    }
}
$appImage = Join-Path $root 'portable\Vector2World'
foreach ($required in @('Vector2World.exe','runtime\bin\java.dll','runtime\bin\server\jvm.dll','app','README_zh-CN.txt','sample\building-sample.geojson','legal','sbom')) {
    if (-not (Test-Path -LiteralPath (Join-Path $appImage $required))) { throw "Portable payload is missing: $required" }
}
$forbidden = Get-ChildItem -LiteralPath $appImage -Recurse -Force | Where-Object {
    $_.Name -in @('node_modules','.env') -or $_.Extension -in @('.pfx','.p12','.key','.pem')
}
if ($forbidden) { throw "Forbidden development or secret-bearing files found: $($forbidden.FullName -join ', ')" }
foreach ($bom in @('java.cdx.json','web.cdx.json')) {
    $document = Get-Content -Raw -LiteralPath (Join-Path $root "sbom\$bom") | ConvertFrom-Json
    if ($document.bomFormat -ne 'CycloneDX' -or -not $document.components) { throw "Invalid CycloneDX SBOM: $bom" }
}
if ($metadata.signingStatus -eq 'SIGNED') {
    $signedTargets = @((Join-Path $appImage 'Vector2World.exe'))
    $signedTargets += @(Get-ChildItem -LiteralPath (Join-Path $root 'installer') -Filter '*.msi' | ForEach-Object FullName)
    foreach ($target in $signedTargets) {
        $signature = Get-AuthenticodeSignature -LiteralPath $target
        if ($signature.Status -ne 'Valid') { throw "Release signature is not valid for ${target}: $($signature.Status)" }
    }
} elseif ($metadata.signingStatus -notin @('UNSIGNED', 'UNSIGNED_RC')) {
    throw "Unknown signing status: $($metadata.signingStatus)"
}
Write-Host 'VECTOR2WORLD_RELEASE_VERIFY_OK'
