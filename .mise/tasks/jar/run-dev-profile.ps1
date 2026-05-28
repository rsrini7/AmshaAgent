#!/usr/bin/env pwsh
#MISE description = "Run packaged jar with dev profile. Usage: mise run jar:run-dev-profile -- serve|chat|attach|ask"
#MISE dir = "{{config_root}}"
#MISE shell = "pwsh -NonInteractive -Command"
$ErrorActionPreference = 'Stop'
$jar = 'arcclaw-app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build-app'; exit 1 }
$cmd = if ($args.Count -gt 0 -and $args[0]) { $args[0] } else { 'serve' }
if ($cmd -eq 'attach' -and -not $env:TELEGRAM_BOT_TOKEN) {
    Write-Host '❌  TELEGRAM_BOT_TOKEN is not set in .env'; exit 1
}
[console]::InputEncoding = [console]::OutputEncoding = [System.Text.Encoding]::UTF8
$jvmArgs = @(
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Dsun.misc.unsafe.memory.access=allow',
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-Dquarkus.profile=dev',
    '-Darcclaw.rag.vec-extension-path=./lib/vec0',
    '-Darcclaw.data-dir=./data'
)
if ($cmd -in @('chat','ask')) {
    $jvmArgs += '-Dquarkus.mcp.server.stdio.enabled=false'
    $jvmArgs += '-Darcclaw.telegram.enabled=false'
}
if ($cmd -eq 'attach') { $jvmArgs += '-Darcclaw.telegram.enabled=true' }
Write-Host "▶   Starting JAR in dev profile - mode: $cmd"
try {
    & java @jvmArgs -jar $jar $cmd
    $script:exitCode = $LASTEXITCODE
} finally {
    $code = if ($null -eq $script:exitCode) { 130 } else { $script:exitCode }
    if ($code -eq 130 -or $code -eq 143) { exit 0 }
    exit $code
}
