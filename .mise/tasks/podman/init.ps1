#!/usr/bin/env pwsh
#MISE description = "macOS: initialise and start Podman machine (6 GB RAM, 4 CPUs, 40 GB disk)"
$ErrorActionPreference = 'Stop'
if ($IsWindows) {
    Write-Host 'ℹ️   podman:init is macOS-only. On Windows use Podman Desktop or Docker Desktop.'
    exit 0
}
podman machine init --memory 6144 --cpus 4 --disk-size 40
podman machine start
Write-Host '✅  Podman machine initialised and running.'
