#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Nvidia on port 8790"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'nvidia' -Port 8790 -TargetApiUrl 'https://integrate.api.nvidia.com/v1'
