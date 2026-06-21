#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Switch active JDK to GraalVM Community 25.0.2 (for native builds)"
$ErrorActionPreference = 'Stop'
mise use --path mise.toml java@graalvm-community-25.0.2
java -version
