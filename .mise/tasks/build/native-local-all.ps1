#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Build all 3 backend variants (sqlite, postgres, fs) - local GraalVM, no container"
$ErrorActionPreference = 'Stop'
$exeSuffix = if ($IsWindows) { '.exe' } else { '' }
Write-Host '═══════════════════════════════════════════════════'
Write-Host '  Building all 3 backend variants (local GraalVM)...'
Write-Host '═══════════════════════════════════════════════════'
Write-Host ''

# 1. SQLite (default) — copies to dist/amsha-sqlite via 'mise run dist:dist'
Write-Host '── [1/3] SQLite (default) ──'
if ($args -contains '--clean' -or $args -contains '-c') {
    mise run build:native-local -- --clean
} else {
    mise run build:native-local
}
if (Test-Path "dist/amsha-sqlite$exeSuffix") {
    Write-Host "✅  SQLite binary built"
} else { Write-Host "❌  SQLite build failed" }

# 2. PostgreSQL — copies to dist/amsha-pg inline
Write-Host ''
Write-Host '── [2/3] PostgreSQL ──'
mise run build:native-local-pg
if (Test-Path "dist/amsha-pg$exeSuffix") {
    Write-Host "✅  PostgreSQL binary built"
} else { Write-Host "❌  PostgreSQL build failed" }

# 3. Filesystem — copies to dist/amsha-fs inline
Write-Host ''
Write-Host '── [3/3] Filesystem ──'
mise run build:native-local-fs
if (Test-Path "dist/amsha-fs$exeSuffix") {
    Write-Host "✅  Filesystem binary built"
} else { Write-Host "❌  Filesystem build failed" }

Write-Host ''
Write-Host '── dist/ folder contents ──'
Get-ChildItem dist -File | Where-Object { $_.Name -like 'amsha*' } | Format-Table Name, @{L='Size';E={'{0:N2} MB' -f ($_.Length/1MB)}} -AutoSize

Write-Host ''
Write-Host '═══════════════════════════════════════════════════'
Write-Host '  All 3 variants built:'
Write-Host '  dist/amsha-sqlite   (SQLite — default)'
Write-Host '  dist/amsha-pg       (PostgreSQL)'
Write-Host '  dist/amsha-fs       (Filesystem)'
Write-Host '═══════════════════════════════════════════════════'
