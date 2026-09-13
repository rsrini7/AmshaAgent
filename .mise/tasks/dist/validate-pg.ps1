#!/usr/bin/env pwsh
#MISE description = "Run fast REST validation against the PostgreSQL distribution"

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$wrapper = Join-Path $repoRoot 'scripts/run_dist_pg.sh'

if (-not (Get-Command bash -ErrorAction SilentlyContinue)) {
    throw "The distribution runner requires bash: $wrapper"
}

& bash $wrapper --validate --fast @args
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
