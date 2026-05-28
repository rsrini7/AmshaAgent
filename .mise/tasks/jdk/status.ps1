#!/usr/bin/env pwsh
#MISE description = "Show which JDK is currently active and list all installed JDKs"
$ErrorActionPreference = 'Stop'
java -version
mise ls java
