#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Build all 3 backend variants (sqlite, postgres, fs) - local GraalVM, no container"
$ErrorActionPreference = 'Stop'
$exeSuffix = if ($IsWindows) { '.exe' } else { '' }
Write-Host '═══════════════════════════════════════════════════'
Write-Host '  Building all 3 backend variants (local GraalVM)...'
Write-Host '═══════════════════════════════════════════════════'
Write-Host ''

# 1. SQLite (default) — copies to dist/arcclaw-sqlite via 'mise run dist'
Write-Host '── [1/3] SQLite (default) ──'
mise run build:native-local
if (Test-Path "dist/arcclaw-sqlite$exeSuffix") {
    Write-Host "✅  SQLite binary built"
} else { Write-Host "❌  SQLite build failed" }

# 2. PostgreSQL — copies to dist/arcclaw-pg inline
Write-Host ''
Write-Host '── [2/3] PostgreSQL ──'
mise run build:native-local-pg
if (Test-Path "dist/arcclaw-pg$exeSuffix") {
    Write-Host "✅  PostgreSQL binary built"
} else { Write-Host "❌  PostgreSQL build failed" }

# 3. Filesystem — copies to dist/arcclaw-fs inline
Write-Host ''
Write-Host '── [3/3] Filesystem ──'
mise run build:native-local-fs
if (Test-Path "dist/arcclaw-fs$exeSuffix") {
    Write-Host "✅  Filesystem binary built"
} else { Write-Host "❌  Filesystem build failed" }

Write-Host ''
Write-Host '── dist/ folder contents ──'
Get-ChildItem dist -File | Where-Object { $_.Name -like 'arcclaw*' } | Format-Table Name, @{L='Size';E={'{0:N2} MB' -f ($_.Length/1MB)}} -AutoSize

Write-Host ''
Write-Host '═══════════════════════════════════════════════════'
Write-Host '  All 3 variants built:'
Write-Host '  dist/arcclaw-sqlite   (SQLite — default)'
Write-Host '  dist/arcclaw-pg       (PostgreSQL)'
Write-Host '  dist/arcclaw-fs       (Filesystem)'
Write-Host '═══════════════════════════════════════════════════'
