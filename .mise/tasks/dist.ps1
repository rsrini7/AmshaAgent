#!/usr/bin/env pwsh
#MISE description = "Assemble dist/ folder: native exe (SQLite only)"
$ErrorActionPreference = 'Stop'
$suffix  = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$runnerSrc = "arcclaw-app-1.0.0-SNAPSHOT$suffix"
$src     = "arcclaw-app/target/$runnerSrc"
$distExe = if ($IsWindows) { "arcclaw-sqlite.exe" } else { "arcclaw-sqlite" }
$distDir = 'dist'

if (-not (Test-Path $src)) {
    Write-Host "❌  Native binary not found at $src - run: mise run build:native-local"
    exit 1
}

if (-not (Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir -Force | Out-Null }

# --- Cleanup dist/ ephemeral data to ensure a clean package ---
if (Test-Path "$distDir/.playwright-browsers") {
    Write-Host "ℹ️   Removing .playwright-browsers from $distDir for clean distribution..."
    Remove-Item -Recurse -Force "$distDir/.playwright-browsers"
}
if (Test-Path "$distDir/.playwright-driver") { Remove-Item -Recurse -Force "$distDir/.playwright-driver" }
if (Test-Path "$distDir/data") { Write-Host "ℹ️   Removing existing data/ from $distDir"; Remove-Item -Recurse -Force "$distDir/data" }
# Clean up old runner files
if (Test-Path "$distDir/arcclaw-runner$suffix") { Remove-Item "$distDir/arcclaw-runner$suffix" -Force }
if (Test-Path "$distDir/arcclaw$suffix") { Remove-Item "$distDir/arcclaw$suffix" -Force }

Copy-Item $src "$distDir/$distExe"

# Create .env to prevent project root .env from being loaded at runtime
if (-not (Test-Path "$distDir/.env")) {
    Set-Content -Path "$distDir/.env" -Value "# ArcClaw SQLite backend configuration." -NoNewline
}

Write-Host ''
Write-Host "✅  dist assembled - binary is self-contained."
Write-Host "Run directly (no run.sh needed):"
Write-Host "  Linux/macOS : ./$distExe serve"
Write-Host "  Windows     : .\\$distExe serve"
Write-Host "On first run with no .env present, .env.example is auto-extracted."
Write-Host ''
Write-Host '-- Binaries in dist/ ----------------------------------------------------'
if (Test-Path 'dist/arcclaw-sqlite')     { Write-Host '  OK  arcclaw-sqlite   (Linux/macOS)' } else { Write-Host '  -   arcclaw-sqlite   (Linux/macOS) -- not built yet' }
if (Test-Path 'dist/arcclaw-sqlite.exe') { Write-Host '  OK  arcclaw-sqlite.exe (Windows)'     } else { Write-Host '  -   arcclaw-sqlite.exe (Windows)     -- not built yet' }
Write-Host '-------------------------------------------------------------------------'
Write-Host '    Build the missing platform binary and re-run: mise run dist'
