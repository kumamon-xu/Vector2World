[CmdletBinding()]
param([ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '1.0.0')
$ErrorActionPreference = 'Stop'
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..\..')).Path

function Invoke-Checked([string]$Name, [string[]]$Arguments) {
    $output = @(& $Name @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) { throw "$Name $($Arguments -join ' ') failed: $($output -join [Environment]::NewLine)" }
    $output
}

$branch = (Invoke-Checked 'git' @('-C',$repoRoot,'branch','--show-current') | Select-Object -Last 1).Trim()
if ($branch -ne 'master') { throw "Production release must start from master, found $branch" }
if (@(Invoke-Checked 'git' @('-C',$repoRoot,'status','--porcelain','--untracked-files=all')).Count -ne 0) {
    throw 'Production release requires a clean working tree'
}
Invoke-Checked 'git' @('-C',$repoRoot,'fetch','origin','master','--tags') | Out-Null
$head = (Invoke-Checked 'git' @('-C',$repoRoot,'rev-parse','HEAD') | Select-Object -Last 1).Trim()
$remote = (Invoke-Checked 'git' @('-C',$repoRoot,'rev-parse','origin/master') | Select-Object -Last 1).Trim()
if ($head -ne $remote) { throw "master is not synchronized with origin/master: $head != $remote" }
$tag = "v$Version"
if (& git -C $repoRoot rev-parse --verify --quiet "refs/tags/$tag") { throw "Local tag already exists: $tag" }
if (& git -C $repoRoot ls-remote --exit-code --tags origin "refs/tags/$tag" 2>$null) { throw "Remote tag already exists: $tag" }
if (& gh release view $tag --repo 'kumamon-xu/Vector2World' 2>$null) { throw "GitHub Release already exists: $tag" }
Invoke-Checked 'gh' @('auth','status') | Out-Null
$secretNames = @(Invoke-Checked 'gh' @('secret','list','--repo','kumamon-xu/Vector2World','--json','name','--jq','.[].name'))
$missing = @('VECTOR2WORLD_SIGN_PFX_BASE64','VECTOR2WORLD_SIGN_PASSWORD') | Where-Object { $_ -notin $secretNames }
if ($missing) { throw "Production signing secrets are missing: $($missing -join ', ')" }
Write-Host "VECTOR2WORLD_PRODUCTION_PREFLIGHT_OK version=$Version commit=$head"
