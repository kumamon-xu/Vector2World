[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Msi,
    [string]$PreviousMsi,
    [string]$ExpectedVersion,
    [string]$LogRoot = "$env:TEMP\Vector2World-installer-test"
)
$ErrorActionPreference = 'Stop'
$msiPath = (Resolve-Path -LiteralPath $Msi).Path
$previousMsiPath = if ($PreviousMsi) { (Resolve-Path -LiteralPath $PreviousMsi).Path } else { $null }
$null = New-Item -ItemType Directory -Force -Path $LogRoot
$markerRoot = Join-Path $env:LOCALAPPDATA 'Vector2World\data'
$null = New-Item -ItemType Directory -Force -Path $markerRoot
$marker = Join-Path $markerRoot 'installer-preservation.marker'
'preserve-user-data' | Set-Content -LiteralPath $marker -Encoding ascii
function Invoke-Msi([string]$Mode, [string[]]$Arguments) {
    $log = Join-Path $LogRoot "$Mode.log"
    $process = Start-Process msiexec.exe -ArgumentList ($Arguments + @('/qn','/norestart','/l*v',$log)) -PassThru -Wait -WindowStyle Hidden
    if ($process.ExitCode -notin @(0, 3010)) { throw "MSI $Mode failed with exit code $($process.ExitCode); see $log" }
}
if ($previousMsiPath) {
    Invoke-Msi 'install-previous' @('/i',$previousMsiPath)
    Invoke-Msi 'upgrade' @('/i',$msiPath)
} else {
    Invoke-Msi 'install' @('/i',$msiPath)
}
if ($ExpectedVersion) {
    $uninstallRoots = @(
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    $installed = $uninstallRoots | Where-Object { Test-Path -LiteralPath $_ } |
        ForEach-Object { Get-ChildItem -LiteralPath $_ -ErrorAction SilentlyContinue } |
        ForEach-Object { Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue } |
        Where-Object { $_.DisplayName -eq 'Vector2World' } |
        Select-Object -First 1
    if (-not $installed -or $installed.DisplayVersion -ne $ExpectedVersion) {
        throw "Installed version mismatch: expected $ExpectedVersion, found $($installed.DisplayVersion)"
    }
}
Invoke-Msi 'repair' @('/fa',$msiPath)
Invoke-Msi 'uninstall' @('/x',$msiPath)
if (-not (Test-Path -LiteralPath $marker)) { throw 'Uninstall removed user data unexpectedly' }
Write-Host 'VECTOR2WORLD_INSTALLER_LIFECYCLE_OK'
