[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$AppImage,
    [Parameter(Mandatory)][string]$WorkRoot
)
$ErrorActionPreference = 'Stop'
$appImage = (Resolve-Path -LiteralPath $AppImage).Path
$workRoot = [System.IO.Path]::GetFullPath($WorkRoot)
if (-not (Test-Path -LiteralPath $workRoot)) { $null = New-Item -ItemType Directory -Path $workRoot }
$exe = Join-Path $appImage 'Vector2World.exe'
$runtimeJava = Join-Path $appImage 'runtime\bin\java.dll'
$runtimeJvm = Join-Path $appImage 'runtime\bin\server\jvm.dll'
if (-not (Test-Path -LiteralPath $exe)) { throw "Packaged launcher is missing: $exe" }
if (-not (Test-Path -LiteralPath $runtimeJava) -or -not (Test-Path -LiteralPath $runtimeJvm)) {
    throw "Embedded jpackage runtime is missing its Java/JVM libraries"
}

$profiles = @('Windows clean profile A', 'Windows clean profile B')
foreach ($profile in $profiles) {
    $dataRoot = Join-Path $workRoot $profile
    $stdout = Join-Path $workRoot "$profile.stdout.log"
    $stderr = Join-Path $workRoot "$profile.stderr.log"
    $instanceId = $profile -replace '[^A-Za-z0-9_-]','-'
    $arguments = "--no-browser --smoke-exit --data-root=`"$dataRoot`" --instance-id=$instanceId"
    $process = Start-Process -FilePath $exe -ArgumentList $arguments -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    if (-not $process.WaitForExit(120000)) { $process.Kill(); throw "Packaged smoke timed out for $profile" }
    if ($process.ExitCode -ne 0) { throw "Packaged smoke failed for $profile with exit code $($process.ExitCode): $(Get-Content -Raw $stderr)" }
    $settings = Join-Path $dataRoot 'config\settings.properties'
    $instanceJobs = @(Get-ChildItem -LiteralPath (Join-Path $dataRoot 'data\instances') -Directory | ForEach-Object { Join-Path $_.FullName 'jobs' })
    if (-not (Test-Path -LiteralPath $settings) -or $instanceJobs.Count -ne 1 -or -not (Test-Path -LiteralPath $instanceJobs[0])) {
        throw "Product data layout validation failed for $profile"
    }
}
$javaProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.ExecutablePath -like "$appImage*" })
if ($javaProcesses.Count -ne 0) { throw 'Packaged smoke left an embedded Java process running' }
Write-Host 'VECTOR2WORLD_PORTABLE_SMOKE_OK'
