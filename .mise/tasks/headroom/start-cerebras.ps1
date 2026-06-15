#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Cerebras on port 8791"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'cerebras' -Port 8791 -TargetApiUrl 'https://api.cerebras.ai/v1'
