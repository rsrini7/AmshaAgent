#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Run packaged jar with filesystem backend - interactive REPL"
$ErrorActionPreference = 'Stop'
$jar = 'arcclaw-app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build:fs'; exit 1 }
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-Dquarkus.profile=cli,filesystem',
    '-Darcclaw.rag.vec-extension-path=./lib/vec0',
    '-Darcclaw.data-dir=./data'
)
& java @jvmArgs -jar $jar chat @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
