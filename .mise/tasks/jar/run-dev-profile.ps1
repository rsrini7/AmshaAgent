#!/usr/bin/env pwsh
#MISE description = "Run the packaged JAR in dev profile. Usage: mise run jar:run-dev-profile -- <Amsha CLI command> [args]"
#MISE dir = "{{config_root}}"
#MISE shell = "pwsh -NonInteractive -Command"
$ErrorActionPreference = 'Stop'
$jar = 'app/target/quarkus-app/quarkus-run.jar'
if (-not (Test-Path $jar)) { Write-Host '❌  JAR not found. Run: mise run build-app'; exit 1 }
$appArgs = if ($args.Count -gt 0) { @($args) } else { @('serve') }
$cmd = $appArgs[0]
if ($cmd -eq 'attach' -and -not $env:TELEGRAM_BOT_TOKEN) {
    Write-Host '❌  TELEGRAM_BOT_TOKEN is not set in .env'; exit 1
}
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
    '-Dquarkus.profile=dev',
    '-Damsha.rag.vec-extension-path=./lib/vec0',
    '-Damsha.data-dir=./data'
)
if ($cmd -in @('chat','ask')) {
    $jvmArgs += '-Dquarkus.mcp.server.stdio.enabled=false'
    $jvmArgs += '-Damsha.telegram.enabled=false'
}
if ($cmd -eq 'attach') { $jvmArgs += '-Damsha.telegram.enabled=true' }
# JDWP debug: set AMSHA_DEBUG=true (or AMSHA_DEBUG=PORT) to enable
if ($env:AMSHA_DEBUG -and $env:AMSHA_DEBUG -ne 'false') {
    $debugPort = if ($env:AMSHA_DEBUG -match '^\d+$') { $env:AMSHA_DEBUG } elseif ($env:AMSHA_DEBUG_PORT) { $env:AMSHA_DEBUG_PORT } else { '5005' }
    $jvmArgs += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$debugPort"
    Write-Host "[jar:dev] JDWP debug enabled on port $debugPort"
    Write-Host "[jar:dev] Attach:  jdb -connect com.sun.jdi.SocketAttach:hostname=localhost,port=$debugPort"
}
Write-Host "▶   Starting JAR in dev profile - mode: $cmd"
try {
    & java @jvmArgs -jar $jar @appArgs
    $script:exitCode = $LASTEXITCODE
} finally {
    $code = if ($null -eq $script:exitCode) { 130 } else { $script:exitCode }
    if ($code -eq 130 -or $code -eq 143) { exit 0 }
    exit $code
}
