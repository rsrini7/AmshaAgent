#!/usr/bin/env pwsh
#MISE description = 'Run packaged jar with PostgreSQL - one-shot. Set $env:GOAL="..."'
$ErrorActionPreference = 'Stop'
$goal = if ($env:GOAL) { $env:GOAL } else { '' }
if (-not $goal) { Write-Host 'Usage: $env:GOAL="..."; mise run jar:pg:ask'; exit 1 }
$jar = 'arcclaw-app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build:pg'; exit 1 }
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dquarkus.profile=postgresql',
    '-Darcclaw.data-dir=./data'
)
$cmdArgs = @('ask', $goal)
if ($env:ROLE) { $cmdArgs += '--role', $env:ROLE }
& java @jvmArgs -jar $jar @cmdArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
