#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Build & run packaged jar with filesystem persistence backend (no database needed)"
$ErrorActionPreference = 'Stop'
$jar = 'arcclaw-app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build:fs'; exit 1 }
$cmd = if ($args.Count -gt 0 -and $args[0]) { $args[0] } else { 'serve' }
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dquarkus.profile=filesystem',
    '-Darcclaw.data-dir=./data'
)
& java @jvmArgs -jar $jar $cmd
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
