#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Create OS-specific release archives from dist/ for GitHub releases (3 archives × 3 backends each)"
$ErrorActionPreference = 'Stop'
$version = '0.1.0'
$out     = 'dist/release'
New-Item -ItemType Directory -Force $out | Out-Null
$packed = 0

# Windows
$winBinaries = @('amsha-sqlite.exe', 'amsha-pg.exe', 'amsha-fs.exe')
$winFound = @()
foreach ($bin in $winBinaries) {
    if (Test-Path "dist/$bin") { $winFound += $bin }
}
if ($winFound.Count -gt 0) {
    $stage = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid())
    New-Item -ItemType Directory $stage | Out-Null
    foreach ($bin in $winFound) { Copy-Item "dist/$bin" "$stage/$bin" }
    $archive = "$out/amsha-$version-windows-x64.zip"
    Compress-Archive -Path "$stage/*" -DestinationPath $archive -Force
    Remove-Item $stage -Recurse -Force
    Write-Host "✅  $archive ($($winFound.Count) binaries)"
    $packed++
} else {
    Write-Host "⚠️   No Windows binaries found in dist/ - run mise run build:native-local-all first"
}

# Linux
$linuxBinaries = @('amsha-sqlite', 'amsha-pg', 'amsha-fs')
$linuxFound = @()
foreach ($bin in $linuxBinaries) {
    if (Test-Path "dist/$bin") { $linuxFound += $bin }
}
if ($linuxFound.Count -gt 0) {
    $stage = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid())
    New-Item -ItemType Directory $stage | Out-Null
    foreach ($bin in $linuxFound) { Copy-Item "dist/$bin" "$stage/$bin" }
    $archive = "$out/amsha-$version-linux-x64.tar.gz"
    if (Get-Command tar -ErrorAction SilentlyContinue) {
        tar -czf $archive -C $stage .
        Write-Host "✅  $archive ($($linuxFound.Count) binaries)"
        $packed++
    } else { Write-Host "⚠️   tar not found -- skipping Linux archive. Run from WSL2." }
    Remove-Item $stage -Recurse -Force
} else {
    Write-Host "⚠️   No Linux binaries found in dist/ - run mise run build:native-local-all first"
}

# macOS
$macosBinaries = @('amsha-sqlite', 'amsha-pg', 'amsha-fs')
$macosFound = @()
foreach ($bin in $macosBinaries) {
    if (Test-Path "dist/$bin") { $macosFound += $bin }
}
if ($macosFound.Count -gt 0) {
    $stage = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid())
    New-Item -ItemType Directory $stage | Out-Null
    foreach ($bin in $macosFound) { Copy-Item "dist/$bin" "$stage/$bin" }
    $archive = "$out/amsha-$version-macos-aarch64.tar.gz"
    if (Get-Command tar -ErrorAction SilentlyContinue) {
        tar -czf $archive -C $stage .
        Write-Host "✅  $archive ($($macosFound.Count) binaries)"
        $packed++
    } else { Write-Host "⚠️   tar not found -- skipping macOS archive. Run from WSL2." }
    Remove-Item $stage -Recurse -Force
} else {
    Write-Host "⚠️   No macOS binaries found in dist/ - run mise run build:native-local-all first"
}

if ($packed -eq 0) {
    Write-Host '❌  No binaries found in dist/ - run mise run build:native-local-all first'; exit 1
}
Write-Host ''
Write-Host "-- Release archives in $out/ --"
Get-ChildItem $out | Format-Table Name, @{L='Size';E={'{0:N0} KB' -f ($_.Length/1KB)}}, @{L='Binaries';E={
    $ext = $_.Extension
    $archive = $_.FullName
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid())
    try {
        if ($ext -eq '.zip') {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [System.IO.Compression.ZipFile]::ExtractToDirectory($archive, $tempDir)
        } else {
            tar -xzf $archive -C $tempDir
        }
        (Get-ChildItem $tempDir -File).Count
    } finally {
        if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
    }
}} -AutoSize
