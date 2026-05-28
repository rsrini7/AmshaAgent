#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Clean Maven build artifacts"
$ErrorActionPreference = 'Stop'
$mvnw = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
& $mvnw clean
Write-Host '✅  Build artifacts cleaned.'
