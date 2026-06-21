#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Openai on port 8793"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'openai' -Port 8793 -TargetApiUrl 'https://api.openai.com/v1'
