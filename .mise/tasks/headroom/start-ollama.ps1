#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Ollama on port 8794"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'ollama' -Port 8794 -TargetApiUrl 'http://localhost:11434'
