[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$')]
    [string]$Version = '1.0.0-rc.1',
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$PackageVersion = '1.0.0',
    [string]$BuildNumber = (Get-Date -Format 'yyyyMMddHHmmss'),
    [string]$OutputRoot,
    [string]$WixBin,
    [switch]$SkipInstaller,
    [switch]$SkipTests,
    [switch]$Release
)

$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path
if (-not $OutputRoot) { $OutputRoot = Join-Path $repoRoot 'output\release' }
$releaseId = "$Version+$BuildNumber"
$releaseRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) $releaseId
if (Test-Path -LiteralPath $releaseRoot) {
    throw "Release '$releaseId' already exists and will not be overwritten: $releaseRoot"
}
$null = New-Item -ItemType Directory -Path $releaseRoot

function Invoke-Native([string]$Name, [string[]]$Arguments, [string]$WorkingDirectory = $repoRoot) {
    Push-Location $WorkingDirectory
    try {
        & $Name @Arguments
        if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}

function Resolve-WixBin {
    if ($WixBin) {
        $resolved = [System.IO.Path]::GetFullPath($WixBin)
        if ((Test-Path (Join-Path $resolved 'candle.exe')) -and (Test-Path (Join-Path $resolved 'light.exe'))) {
            return $resolved
        }
        throw "WixBin does not contain candle.exe and light.exe: $resolved"
    }
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if ($candle -and $light) { return (Split-Path -Parent $candle.Source) }
    return $null
}

function New-ProductIcon([string]$Path) {
    Add-Type -AssemblyName System.Drawing
    $bitmap = [System.Drawing.Bitmap]::new(64, 64)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.Clear([System.Drawing.Color]::FromArgb(7, 23, 34))
    $teal = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(82, 221, 208))
    $navy = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(7, 23, 34))
    $graphics.FillRectangle($teal, 10, 10, 44, 44)
    $graphics.FillRectangle($navy, 20, 20, 24, 24)
    $graphics.FillRectangle($teal, 27, 27, 10, 10)
    $icon = [System.Drawing.Icon]::FromHandle($bitmap.GetHicon())
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew)
    try { $icon.Save($stream) } finally {
        $stream.Dispose(); $icon.Dispose(); $graphics.Dispose(); $teal.Dispose(); $navy.Dispose(); $bitmap.Dispose()
    }
}

function New-JavaSbom([string]$AttributionPath, [string]$Destination) {
    [xml]$report = Get-Content -Raw -LiteralPath $AttributionPath
    $components = @($report.attributionReport.dependencies.dependency | ForEach-Object {
        $licenses = @($_.licenses.license | ForEach-Object {
            if ($_.name) { @{ license = @{ name = [string]$_.name; url = [string]$_.url } } }
        })
        $component = [ordered]@{
            type = 'library'; group = [string]$_.groupId; name = [string]$_.artifactId
            version = [string]$_.version; purl = "pkg:maven/$($_.groupId)/$($_.artifactId)@$($_.version)"
        }
        if ($licenses.Count -gt 0) { $component.licenses = $licenses }
        $component
    })
    $bom = [ordered]@{
        bomFormat = 'CycloneDX'; specVersion = '1.5'; serialNumber = "urn:uuid:$([guid]::NewGuid())"; version = 1
        metadata = @{ component = @{ type = 'application'; name = 'Vector2World'; version = $Version } }
        components = $components
    }
    $bom | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Destination -Encoding utf8
}

function New-ThirdPartyReport([string]$AttributionPath, [string]$Destination) {
    [xml]$report = Get-Content -Raw -LiteralPath $AttributionPath
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('Vector2World third-party dependency report')
    $lines.Add("Generated for $Version ($BuildNumber)")
    $lines.Add('')
    foreach ($dependency in $report.attributionReport.dependencies.dependency) {
        $lines.Add("$($dependency.groupId):$($dependency.artifactId):$($dependency.version)")
        foreach ($license in @($dependency.licenses.license)) {
            if ($license.name) { $lines.Add("  License: $($license.name) $($license.url)") }
        }
    }
    $lines | Set-Content -LiteralPath $Destination -Encoding utf8
}

$gitSha = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve Git SHA' }
$dirtyLines = @(& git -C $repoRoot status --porcelain --untracked-files=normal)
$gitDirty = $dirtyLines.Count -gt 0
if ($Release) {
    if ($gitDirty) { throw 'A production release must be built from a clean Git tree' }
    $tag = (& git -C $repoRoot describe --exact-match --tags HEAD 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $tag -ne "v$Version") { throw "HEAD must have exact tag v$Version" }
}
$buildTime = [DateTimeOffset]::UtcNow.ToString('o')

if (-not $SkipTests) {
    Invoke-Native 'npm.cmd' @('ci') (Join-Path $repoRoot 'spike-viewer')
    Invoke-Native 'npm.cmd' @('audit', '--omit=dev', '--audit-level=high', '--registry=https://registry.npmjs.org') (Join-Path $repoRoot 'spike-viewer')
    Invoke-Native 'npm.cmd' @('run', 'check') (Join-Path $repoRoot 'spike-viewer')
}

$mavenArgs = @(
    '-pl', 'building-tiler-backend', '-am', 'clean', 'package',
    "-Dvector2world.release.version=$Version", "-Dvector2world.build.number=$BuildNumber",
    "-Dvector2world.git.sha=$gitSha", "-Dvector2world.git.dirty=$($gitDirty.ToString().ToLowerInvariant())",
    "-Dvector2world.build.time=$buildTime", '-Dvector2world.packaged=true'
)
if ($SkipTests) { $mavenArgs += '-DskipTests' }
Invoke-Native 'mvn.cmd' $mavenArgs

$backendTarget = Join-Path $repoRoot 'building-tiler-backend\target'
$jar = Get-ChildItem -LiteralPath $backendTarget -Filter 'building-tiler-backend-*.jar' |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
if (-not $jar) { throw 'Repackaged backend JAR was not produced' }
$staging = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'staging')
$input = New-Item -ItemType Directory -Path (Join-Path $staging 'input')
Copy-Item -LiteralPath $jar.FullName -Destination $input.FullName
$iconPath = Join-Path $staging 'Vector2World.ico'
New-ProductIcon $iconPath

$sampleDirectory = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'sample')
$sample = @'
{"type":"FeatureCollection","name":"Vector2World-M6-sample","crs":{"type":"name","properties":{"name":"EPSG:4326"}},"features":[{"type":"Feature","id":"sample-1","properties":{"Elevation":18.5},"geometry":{"type":"Polygon","coordinates":[[[114.1690,22.3190],[114.1693,22.3190],[114.1693,22.3192],[114.1690,22.3192],[114.1690,22.3190]]]}}]}
'@
$sample | Set-Content -LiteralPath (Join-Path $sampleDirectory 'building-sample.geojson') -Encoding utf8NoBOM

$legal = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'legal')
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE.txt') -Destination (Join-Path $legal 'OSM2World-LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $scriptRoot 'THIRD-PARTY-NOTICES.txt') -Destination $legal.FullName
New-ThirdPartyReport (Join-Path $backendTarget 'attribution.xml') (Join-Path $legal 'JAVA-DEPENDENCIES.txt')
$sbom = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'sbom')
New-JavaSbom (Join-Path $backendTarget 'attribution.xml') (Join-Path $sbom 'java.cdx.json')
$npmSbom = & npm.cmd sbom --omit=dev --sbom-format=cyclonedx --prefix (Join-Path $repoRoot 'spike-viewer')
if ($LASTEXITCODE -ne 0) { throw 'npm SBOM generation failed' }
$npmSbom | Set-Content -LiteralPath (Join-Path $sbom 'web.cdx.json') -Encoding utf8

$modules = @(
    'java.base','java.desktop','java.instrument','java.logging','java.management','java.naming','java.net.http','java.prefs',
    'java.rmi','java.scripting','java.security.jgss','java.security.sasl','java.sql','java.transaction.xa',
    'java.xml','jdk.crypto.ec','jdk.unsupported','jdk.zipfs'
) -join ','
$portableDest = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'portable')
$jpackageCommon = @(
    '--input', $input.FullName, '--name', 'Vector2World', '--main-jar', $jar.Name,
    '--main-class', 'org.springframework.boot.loader.launch.JarLauncher', '--app-version', $PackageVersion,
    '--vendor', 'Vector2World contributors', '--description', 'Local building vector to 3D Tiles workbench',
    '--copyright', 'Copyright 2010-2026 OSM2World contributors', '--icon', $iconPath,
    '--add-modules', $modules, '--java-options', '-Xms256m', '--java-options', '-Xmx3g',
    '--java-options', '-XX:+UseG1GC', '--win-console'
)
Invoke-Native 'jpackage.exe' (@('--type', 'app-image', '--dest', $portableDest.FullName) + $jpackageCommon)
$appImage = Join-Path $portableDest 'Vector2World'
Copy-Item -LiteralPath (Join-Path $scriptRoot 'README_zh-CN.txt') -Destination $appImage
Copy-Item -LiteralPath $sampleDirectory -Destination $appImage -Recurse
Copy-Item -LiteralPath $legal -Destination $appImage -Recurse
Copy-Item -LiteralPath $sbom -Destination $appImage -Recurse

$signTool = Get-Command signtool.exe -ErrorAction SilentlyContinue
$pfx = $env:VECTOR2WORLD_SIGN_PFX
$pfxPassword = $env:VECTOR2WORLD_SIGN_PASSWORD
$signingStatus = 'UNSIGNED_RC'
if ($pfx) {
    if (-not $signTool) { throw 'VECTOR2WORLD_SIGN_PFX is set but signtool.exe is unavailable' }
    Invoke-Native $signTool.Source @('sign','/fd','SHA256','/td','SHA256','/tr','http://timestamp.digicert.com','/f',$pfx,'/p',$pfxPassword,(Join-Path $appImage 'Vector2World.exe'))
    $signingStatus = 'SIGNED'
} elseif ($Release) { throw 'Production release signing credentials are required' }

& (Join-Path $scriptRoot 'smoke-release.ps1') -AppImage $appImage -WorkRoot (Join-Path $releaseRoot 'smoke profiles')
if ($LASTEXITCODE -ne 0) { throw 'Portable smoke test failed' }

$portableZip = Join-Path $releaseRoot "Vector2World-$releaseId-windows-x64-portable.zip"
Compress-Archive -LiteralPath $appImage -DestinationPath $portableZip -CompressionLevel Optimal

$installerPath = $null
if (-not $SkipInstaller) {
    $resolvedWix = Resolve-WixBin
    if (-not $resolvedWix) { throw 'WiX 3 candle.exe/light.exe are required to build the MSI; pass -WixBin or use -SkipInstaller for a portable-only RC' }
    $oldPath = $env:PATH
    try {
        $env:PATH = "$resolvedWix;$oldPath"
        $installerDest = New-Item -ItemType Directory -Path (Join-Path $releaseRoot 'installer')
        Invoke-Native 'jpackage.exe' (@(
            '--type','msi','--dest',$installerDest.FullName,'--win-dir-chooser','--win-menu','--win-shortcut',
            '--win-per-user-install',
            '--win-menu-group','Vector2World','--win-upgrade-uuid','4cdd6605-53c4-4b83-ae12-6c88dafab8bf'
        ) + $jpackageCommon)
        $installerPath = (Get-ChildItem -LiteralPath $installerDest -Filter '*.msi' | Select-Object -First 1).FullName
    } finally { $env:PATH = $oldPath }
    if (-not $installerPath) { throw 'MSI was not produced' }
    if ($pfx) {
        Invoke-Native $signTool.Source @('sign','/fd','SHA256','/td','SHA256','/tr','http://timestamp.digicert.com','/f',$pfx,'/p',$pfxPassword,$installerPath)
    }
}

$javaVersionLines = @(& java -version 2>&1 | ForEach-Object { $_.ToString() })
$javaRuntimeLine = if ($javaVersionLines.Count -gt 0) { $javaVersionLines[0] } else { 'Java 17 (version output unavailable)' }
$artifactNames = @([System.IO.Path]::GetRelativePath($releaseRoot, $portableZip).Replace('\','/'))
if ($installerPath) { $artifactNames += [System.IO.Path]::GetRelativePath($releaseRoot, $installerPath).Replace('\','/') }
$metadata = [ordered]@{
    schemaVersion = '1.0'; product = 'Vector2World'; version = $Version; packageVersion = $PackageVersion
    buildNumber = $BuildNumber; gitSha = $gitSha; gitDirty = $gitDirty; buildTime = $buildTime
    osm2worldCommit = 'bfa31df1124295721ec848273fbf93ab46b24d25'
    ruleVersion = 'm2-rules-v1'; presetVersion = 'm2-presets-v1'; signingStatus = $signingStatus
    javaRuntime = $javaRuntimeLine; modules = $modules.Split(',')
    artifacts = $artifactNames
}
$metadataPath = Join-Path $releaseRoot 'release-metadata.json'
$metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $metadataPath -Encoding utf8

$hashTargets = @($portableZip, $metadataPath, (Join-Path $sbom 'java.cdx.json'), (Join-Path $sbom 'web.cdx.json'))
if ($installerPath) { $hashTargets += $installerPath }
$hashLines = foreach ($target in $hashTargets) {
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath $target
    "$($hash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetRelativePath($releaseRoot, $target).Replace('\','/'))"
}
$hashLines | Set-Content -LiteralPath (Join-Path $releaseRoot 'SHA256SUMS.txt') -Encoding ascii

& (Join-Path $scriptRoot 'verify-release.ps1') -ReleaseRoot $releaseRoot
if ($LASTEXITCODE -ne 0) { throw 'Release verification failed' }
Write-Host "VECTOR2WORLD_RELEASE_OK $releaseRoot"
