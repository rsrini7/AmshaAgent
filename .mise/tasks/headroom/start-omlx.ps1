#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Omlx on port 8795"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'omlx' -Port 8795 -TargetApiUrl 'http://127.0.0.1:8000/v1'
