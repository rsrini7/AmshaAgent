#!/usr/bin/env pwsh
#MISE description = "Run the filesystem native distribution backend"

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$wrapper = Join-Path $repoRoot 'scripts/run_dist_fs.sh'

if (-not (Get-Command bash -ErrorAction SilentlyContinue)) {
    throw "The distribution runner requires bash: $wrapper"
}

& bash $wrapper @args
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
