#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Assemble dist/ folder: native exe (SQLite only)"
$ErrorActionPreference = 'Stop'
$suffix  = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$runnerSrc = "amsha-app-0.1.0-SNAPSHOT$suffix"
$src     = "app/target/$runnerSrc"
$distExe = if ($IsWindows) { "amsha-sqlite.exe" } else { "amsha-sqlite" }
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
if (Test-Path "$distDir/data") {
    if ($args -contains '--clean' -or $args -contains '-c') {
        Write-Host "ℹ️   Removing existing data/ from $distDir (clean build)..."
        Remove-Item -Recurse -Force "$distDir/data"
    } else {
        Write-Host "ℹ️   Preserving existing data/ in $distDir (use --clean to wipe)"
    }
}
# Clean up old runner files
if (Test-Path "$distDir/amsha-runner$suffix") { Remove-Item "$distDir/amsha-runner$suffix" -Force }
if (Test-Path "$distDir/amsha$suffix") { Remove-Item "$distDir/amsha$suffix" -Force }

Copy-Item $src "$distDir/$distExe"

# Create .env to prevent project root .env from being loaded at runtime
if (-not (Test-Path "$distDir/.env")) {
    Set-Content -Path "$distDir/.env" -Value "# Amsha Agent SQLite backend configuration." -NoNewline
}

Write-Host ''
Write-Host "✅  dist assembled - binary is self-contained."
Write-Host "Run directly (no run.sh needed):"
Write-Host "  Linux/macOS : ./$distExe serve"
Write-Host "  Windows     : .\\$distExe serve"
Write-Host "On first run with no .env present, .env.example is auto-extracted."
Write-Host ''
Write-Host '-- Binaries in dist/ ----------------------------------------------------'
if (Test-Path 'dist/amsha-sqlite')     { Write-Host '  OK  amsha-sqlite   (Linux/macOS)' } else { Write-Host '  -   amsha-sqlite   (Linux/macOS) -- not built yet' }
if (Test-Path 'dist/amsha-sqlite.exe') { Write-Host '  OK  amsha-sqlite.exe (Windows)'     } else { Write-Host '  -   amsha-sqlite.exe (Windows)     -- not built yet' }
Write-Host '-------------------------------------------------------------------------'
Write-Host '    Build the missing platform binary and re-run: mise run dist:dist'
