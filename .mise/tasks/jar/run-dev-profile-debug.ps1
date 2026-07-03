#!/usr/bin/env pwsh
#MISE description = "Run packaged jar with dev profile + JDWP debug on :5005. Usage: mise run jar:run-dev-profile-debug -- serve|chat|attach|ask"
#MISE dir = "{{config_root}}"
#MISE shell = "pwsh -NonInteractive -Command"
$env:AMSHA_DEBUG = if ($env:AMSHA_DEBUG_PORT) { $env:AMSHA_DEBUG_PORT } else { 'true' }
$cmd = if ($args.Count -gt 0 -and $args[0]) { $args[0] } else { 'serve' }
mise run jar:run-dev-profile $cmd
$code = $LASTEXITCODE
if ($code -eq 130 -or $code -eq 143) { exit 0 }
exit $code
