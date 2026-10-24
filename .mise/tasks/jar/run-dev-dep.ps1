#!/usr/bin/env pwsh
#MISE description = "Build and run the packaged JAR in dev profile. Usage: mise run jar:run-dev-dep -- <Amsha CLI command> [args]"
#MISE dir = "{{config_root}}"
#MISE shell = "pwsh -NonInteractive -Command"
#MISE depends = ["env:recover", "dev:kill", "build:full"]
$appArgs = if ($args.Count -gt 0) { @($args) } else { @('serve') }
mise run jar:run-dev-profile -- @appArgs
$code = $LASTEXITCODE
if ($code -eq 130 -or $code -eq 143) { exit 0 }
exit $code
