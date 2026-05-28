#!/usr/bin/env pwsh
#MISE description = "Switch active JDK back to Temurin 25 (default - dev, build, jar tasks)"
$ErrorActionPreference = 'Stop'
mise use --path mise.toml java@temurin-25
java -version
