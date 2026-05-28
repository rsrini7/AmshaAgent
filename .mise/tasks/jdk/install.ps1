#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Install both JDKs (temurin-25 + graalvm-community-25.0.2) - safe to re-run"
$ErrorActionPreference = 'Stop'
mise install
mise install java@graalvm-community-25.0.2 --quiet
mise ls java
