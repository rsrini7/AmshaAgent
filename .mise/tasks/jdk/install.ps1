#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Install both JDKs (temurin-25 + oracle-graalvm-25.0.5) - safe to re-run"
$ErrorActionPreference = 'Stop'
mise install
$graalVersion = if ($env:AMSHA_GRAALVM_VERSION) { $env:AMSHA_GRAALVM_VERSION } else { 'oracle-graalvm-25.0.5' }
mise install "java@$graalVersion" --quiet
mise ls java
