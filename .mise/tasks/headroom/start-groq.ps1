#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Groq on port 8789"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'groq' -Port 8789 -TargetApiUrl 'https://api.groq.com/openai/v1'
