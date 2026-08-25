#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Assemble dist/ folder: all 3 backend variants (amsha-sqlite, amsha-pg, amsha-fs)"
$ErrorActionPreference = 'Stop'
$suffix  = if ($IsWindows) { '-runner.exe' } else { '-runner' }
$src     = "app/target/amsha-app-0.1.0-SNAPSHOT$suffix"
$distDir = 'dist'

if (-not (Test-Path $src)) {
    Write-Host "❌  Native binary not found at $src - run: mise run build:native-local-all"
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
if (Test-Path "$distDir/amsha-pg-runner$suffix") { Remove-Item "$distDir/amsha-pg-runner$suffix" -Force }
if (Test-Path "$distDir/amsha-fs-runner$suffix") { Remove-Item "$distDir/amsha-fs-runner$suffix" -Force }
if (Test-Path "$distDir/amsha$suffix") { Remove-Item "$distDir/amsha$suffix" -Force }

# Copy all 3 backend variants
$backends = @('amsha-sqlite', 'amsha-pg', 'amsha-fs')
foreach ($backend in $backends) {
    $backendSuffix = if ($IsWindows) { "$backend.exe" } else { $backend }
    $backendSrc = "app/target/amsha-app-0.1.0-SNAPSHOT$suffix"
    Copy-Item $backendSrc "$distDir/$backendSuffix" -Force
    Write-Host "✅  Copied $backendSuffix"
}

# Create .env.fs for the filesystem backend (activates %filesystem profile at runtime)
$envFs = @"
# Environment overrides for amsha-fs binary.
# Source this before running:  source .env.fs && ./amsha-fs serve
QUARKUS_PROFILE=prod,filesystem
"@
Set-Content -Path "$distDir/.env.fs" -Value $envFs -NoNewline
Write-Host "✅  Created .env.fs (source before running amsha-fs)"

Write-Host ''
Write-Host "✅  dist assembled - all 3 backend variants ready."
Write-Host "Run dist:pack to create release archives."
Write-Host ''
Write-Host '-- Binaries in dist/ ----------------------------------------------------'
Get-ChildItem $distDir -File | Where-Object { $_.Name -like 'amsha*' } | Format-Table Name, @{L='Size';E={'{0:N2} MB' -f ($_.Length/1MB)}} -AutoSize
