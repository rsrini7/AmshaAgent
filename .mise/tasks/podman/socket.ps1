#!/usr/bin/env pwsh
#MISE description = "WSL2/Linux: enable Podman user socket via systemd (run once per WSL2 instance)"
$ErrorActionPreference = 'Stop'
if ($IsWindows) {
    Write-Host 'ℹ️   podman:socket is WSL2/Linux-only. On Windows, Podman Desktop manages the socket.'
    exit 0
}
systemctl --user enable --now podman.socket
$uid  = & id -u
$sock = "/run/user/$uid/podman/podman.sock"
ls -l $sock
Write-Host ''
Write-Host '✅  Podman socket active.'
Write-Host "    Add to ~/.bashrc or ~/.zshrc:"
Write-Host "    export DOCKER_HOST=`"unix://$sock`""
