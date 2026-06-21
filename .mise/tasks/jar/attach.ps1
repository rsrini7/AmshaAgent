#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Run packaged jar - Telegram channel daemon (requires TELEGRAM_BOT_TOKEN in .env)"
$ErrorActionPreference = 'Stop'
$jar = 'app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build-app'; exit 1 }
if (-not $env:TELEGRAM_BOT_TOKEN) { Write-Host '❌  TELEGRAM_BOT_TOKEN is not set in .env'; exit 1 }
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    '--add-modules=jdk.incubator.vector',
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-Dquarkus.profile=cli',
    '-Damsha.telegram.enabled=true',
    '-Damsha.rag.vec-extension-path=./lib/vec0',
    '-Damsha.data-dir=./data'
)
& java @jvmArgs -jar $jar attach
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
