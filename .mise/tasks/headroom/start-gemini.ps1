#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Start Headroom proxy for Gemini on port 8792"

. "$PSScriptRoot/headroom-common.ps1"
Start-HeadroomProxy -Provider 'gemini' -Port 8792 -Backend 'gemini'
