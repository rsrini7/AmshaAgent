#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Switch active JDK to GraalVM 25.0.5 (for native builds)"
$ErrorActionPreference = 'Stop'
$graalVersion = if ($env:AMSHA_GRAALVM_VERSION) { $env:AMSHA_GRAALVM_VERSION } else { 'oracle-graalvm-25.0.5' }
mise use --path mise.toml "java@$graalVersion"
java -version
