#!/usr/bin/env pwsh
#MISE description = "WSL2: fix Windows CRLF line endings in mvnw + all .mise task scripts"
$ErrorActionPreference = 'Stop'
if ($IsWindows) {
    Write-Host 'ℹ️   Not needed on native Windows - CRLF fix is for WSL2/Linux only.'
    exit 0
}
if (-not (Get-Command dos2unix -ErrorAction SilentlyContinue)) {
    Write-Host '📦 Installing dos2unix...'
    if ($IsLinux -and (Get-Command apt-get -ErrorAction SilentlyContinue)) {
        sudo apt-get install -y dos2unix
    } elseif ($IsMacOS -and (Get-Command brew -ErrorAction SilentlyContinue)) {
        brew install dos2unix
    } else {
        Write-Host '⚠️  dos2unix not found and no known package manager available. Please install it manually.'
    }
}
foreach ($f in @('mvnw', 'mise.toml', 'arcclaw-ui/package.json')) {
    if (Test-Path $f) { dos2unix $f }
}
if (Test-Path 'mvnw') { chmod +x mvnw }
if (Test-Path '.mise') {
    Get-ChildItem -Path '.mise/' -Recurse -Include '*.sh','*.ps1' | ForEach-Object {
        dos2unix $_.FullName
        chmod +x $_.FullName
        Write-Host "✅  Fixed: $($_.Name)"
    }
}
Write-Host '✅  mvnw + mise.toml + all task scripts CRLF fixed.'
