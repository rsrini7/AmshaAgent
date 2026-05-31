#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Alias: redirects to build:native-linux (container) or build:native-local (macOS/Windows)"
$ErrorActionPreference = 'Stop'
if ($IsMacOS -or $IsWindows) {
    $os = if ($IsMacOS) { 'macOS' } else { 'Windows' }
    Write-Host "❌   build:native produces a Linux ELF binary via Podman — cannot run on $os."
    Write-Host '    Use: mise run build:native-local'
    exit 1
}
mise run build:native-linux
