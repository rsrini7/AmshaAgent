#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Openrouter on port 8788"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'openrouter' -Port 8788 -Backend 'openrouter'
