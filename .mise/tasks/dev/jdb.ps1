#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Attach JDB to a running Quarkus dev instance on :5005 (run dev:debug first)"
#MISE dir = "{{config_root}}"
$ErrorActionPreference = 'Stop'

$debugPort = if ($env:AMSHA_DEBUG_PORT) { $env:AMSHA_DEBUG_PORT } else { '5005' }

Write-Host "[dev:jdb] Attaching JDB to localhost:$debugPort ..."
Write-Host "[dev:jdb] Tip: use 'threads', 'where <id>', 'locals', 'print <expr>'"
Write-Host ""

jdb -connect "com.sun.jdi.SocketAttach:hostname=localhost,port=$debugPort"
