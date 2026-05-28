#!/usr/bin/env pwsh
#MISE description = "Run packaged jar - REST daemon on :8080 (CLI profile)"
$ErrorActionPreference = 'Stop'
$jar = 'arcclaw-app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build-app'; exit 1 }
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-Dquarkus.profile=cli',
    '-Dquarkus.mcp.server.stdio.enabled=true',
    '-Darcclaw.telegram.enabled=true',
    '-Darcclaw.rag.vec-extension-path=./lib/vec0',
    '-Darcclaw.data-dir=./data'
)
& java @jvmArgs -jar $jar serve
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
