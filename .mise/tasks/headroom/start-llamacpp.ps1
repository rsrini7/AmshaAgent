#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Llamacpp on port 8797"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'llamacpp' -Port 8797 -TargetApiUrl 'http://localhost:9000/v1'
