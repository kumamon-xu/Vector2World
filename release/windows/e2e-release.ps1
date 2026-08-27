[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppImage,
    [Parameter(Mandatory)][string]$WorkRoot
)
$ErrorActionPreference = 'Stop'
$appImage = (Resolve-Path -LiteralPath $AppImage).Path
$workRoot = [System.IO.Path]::GetFullPath($WorkRoot)
$null = New-Item -ItemType Directory -Force -Path $workRoot
$exe = Join-Path $appImage 'Vector2World.exe'
$sample = Join-Path $appImage 'sample\building-sample.geojson'
foreach ($required in @($exe, $sample)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Packaged E2E input is missing: $required" }
}

function Get-AppProcesses {
    @(Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith($appImage, [StringComparison]::OrdinalIgnoreCase)
    })
}
function Resolve-LocalUri([string]$Base, [string]$Path) {
    if ([Uri]::IsWellFormedUriString($Path, [UriKind]::Absolute)) { return $Path }
    return ([Uri]::new([Uri]$Base, $Path)).AbsoluteUri
}
function Invoke-Json([string]$Uri, [string]$Method = 'GET', $Body = $null) {
    $arguments = @{ Uri = $Uri; Method = $Method; Headers = @{ Accept = 'application/json' } }
    if ($null -ne $Body) {
        $arguments.ContentType = 'application/json'
        $arguments.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
    }
    Invoke-RestMethod @arguments
}

$beforePids = @(Get-AppProcesses | ForEach-Object ProcessId)
$stdout = Join-Path $workRoot 'packaged-e2e.stdout.log'
$stderr = Join-Path $workRoot 'packaged-e2e.stderr.log'
$dataRoot = Join-Path $workRoot 'Windows 打包 E2E 数据'
$arguments = "--no-browser --data-root=`"$dataRoot`" --instance-id=release-e2e"
$launcher = Start-Process -FilePath $exe -ArgumentList $arguments -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr
try {
    $deadline = (Get-Date).AddSeconds(60)
    $readyLine = $null
    while ((Get-Date) -lt $deadline -and -not $readyLine) {
        Start-Sleep -Milliseconds 500
        if (Test-Path -LiteralPath $stdout) {
            $readyLine = Get-Content -LiteralPath $stdout | Where-Object { $_ -like 'VECTOR2WORLD_READY *' } | Select-Object -Last 1
        }
    }
    if (-not $readyLine) { throw "Packaged E2E server did not become ready: $(Get-Content -Raw -LiteralPath $stderr -ErrorAction SilentlyContinue)" }
    $baseUri = $readyLine.Substring('VECTOR2WORLD_READY '.Length).Trim()

    $health = Invoke-Json (Resolve-LocalUri $baseUri '/api/system/health')
    if ($health.status -ne 'UP') { throw 'Packaged health endpoint is not UP' }
    $about = Invoke-Json (Resolve-LocalUri $baseUri '/api/system/about')
    if (-not $about.version -or -not $about.gitSha -or -not $about.osm2worldCommit) { throw 'Packaged About metadata is incomplete' }
    foreach ($path in @('/', '/generate', '/cesiumStatic/Assets/approximateTerrainHeights.json')) {
        $response = Invoke-WebRequest -Uri (Resolve-LocalUri $baseUri $path) -UseBasicParsing
        if ($response.StatusCode -ne 200) { throw "Packaged static resource failed: $path" }
    }

    $dataset = Invoke-RestMethod -Uri (Resolve-LocalUri $baseUri '/api/datasets') -Method Post `
        -Headers @{ Accept = 'application/json' } -Form @{ file = Get-Item -LiteralPath $sample }
    if ($dataset.featureCount -ne 1 -or $dataset.crs -ne 'EPSG:4326') { throw 'Packaged sample import returned unexpected metadata' }
    $mapping = Invoke-Json (Resolve-LocalUri $baseUri "/api/datasets/$($dataset.datasetId)/height-mapping") 'POST' @{
        fieldName = 'Elevation'; unit = 'm'; invalidPolicy = 'SKIP'; maximumHeightMeters = 10000
    }
    if ($mapping.heightQuality.valid -ne 1) { throw 'Packaged Elevation/m mapping did not yield one valid building' }

    $modeling = [ordered]@{
        datasetId = $dataset.datasetId; heightField = 'Elevation'; heightUnit = 'm'; invalidPolicy = 'SKIP'
        maximumHeightMeters = 10000; ruleVersion = 'm2-rules-v1'; roofMode = 'AUTO_SIMPLE'
        stylePreset = 'neutral-city'; floorHeightMeters = 3.2; roofHeightRatio = 0.15
        minimumRoofHeightMeters = 0.8; maximumRoofHeightMeters = 3.0; minimumBodyHeightMeters = 2.5
        minimumPitchedBuildingHeightMeters = 6.0; maximumPitchedBuildingHeightMeters = 30.0
        variantSeed = 1446139724
    }
    $previewBody = [ordered]@{} + $modeling
    $previewBody.lod = 2; $previewBody.sampleSize = 100
    $preview = Invoke-Json (Resolve-LocalUri $baseUri '/api/model-previews') 'POST' $previewBody
    if ($preview.status -ne 'READY' -or $preview.modeledBuildings -ne 1) { throw 'Packaged preview did not become READY' }
    $previewTileset = Invoke-WebRequest -Uri (Resolve-LocalUri $baseUri $preview.links.tileset) -UseBasicParsing
    if ($previewTileset.StatusCode -ne 200) { throw 'Packaged preview tileset is unavailable' }

    $jobBody = [ordered]@{} + $modeling
    $jobBody.zoom = 15; $jobBody.lods = @(2); $jobBody.workerCount = 2; $jobBody.outputFormats = @('3DTILES')
    $job = Invoke-Json (Resolve-LocalUri $baseUri '/api/jobs') 'POST' $jobBody
    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Milliseconds 250
        $job = Invoke-Json (Resolve-LocalUri $baseUri "/api/jobs/$($job.id)")
    } while ($job.state -notin @('COMPLETED','COMPLETED_WITH_WARNINGS','FAILED','CANCELLED') -and (Get-Date) -lt $deadline)
    if ($job.state -notin @('COMPLETED','COMPLETED_WITH_WARNINGS')) { throw "Packaged generation ended in $($job.state): $($job.error)" }
    $report = Invoke-Json (Resolve-LocalUri $baseUri $job.links.report)
    $manifest = Invoke-Json (Resolve-LocalUri $baseUri $job.links.manifest)
    $tileset = Invoke-WebRequest -Uri (Resolve-LocalUri $baseUri $job.links.tileset) -UseBasicParsing
    if ($report.successfulTiles -ne 1 -or $report.failedTiles -ne 0 -or $tileset.StatusCode -ne 200) {
        throw 'Packaged generation report or root tileset validation failed'
    }
    if ($manifest.applicationVersion -ne $about.version -or $manifest.applicationGitSha -ne $about.gitSha) {
        throw 'Packaged About and generation manifest metadata differ'
    }
    Write-Host 'VECTOR2WORLD_PACKAGED_E2E_OK'
} finally {
    $newProcesses = @(Get-AppProcesses | Where-Object { $_.ProcessId -notin $beforePids })
    foreach ($process in $newProcesses) { Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue }
    if (-not $launcher.HasExited) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue }
}
