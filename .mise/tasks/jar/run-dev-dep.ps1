#!/usr/bin/env pwsh
#MISE description = "Build and run packaged jar with dev profile. Usage: mise run jar:run-dev-dep -- serve|chat|attach|ask"
#MISE dir = "{{config_root}}"
#MISE shell = "pwsh -NonInteractive -Command"
#MISE depends = ["env:recover", "dev:kill", "build:full"]
$cmd = if ($args.Count -gt 0 -and $args[0]) { $args[0] } else { 'serve' }
mise run jar:run-dev-profile $cmd
$code = $LASTEXITCODE
if ($code -eq 130 -or $code -eq 143) { exit 0 }
exit $code
