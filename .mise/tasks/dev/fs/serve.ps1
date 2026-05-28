#!/usr/bin/env pwsh
#MISE description = "Dev mode with filesystem persistence backend (no database needed)"
$ErrorActionPreference = 'Stop'
$envCopied = $false
try {
    if (-not (Test-Path '.env') -and (Test-Path '../.env')) {
        Copy-Item -Path '../.env' -Destination '.env'
        $envCopied = $true
        Write-Host '[dev:fs] Copied ../.env -> arcclaw-app/.env'
    }
    $mvnw    = if ($IsWindows) { '..\mvnw.cmd' } else { '../mvnw' }
    $jvmArgs = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8'
    & $mvnw quarkus:dev `
        '--no-transfer-progress' `
        "-Djvm.args=$jvmArgs" `
        '-Dquarkus.args=serve' `
        '-Dquarkus.profile=dev,filesystem' `
        '-Dquarkus.datasource.enabled=false' `
        '-Dquarkus.flyway.enabled=false' `
        '-Darcclaw.persistence.backend=fs' `
        '-Darcclaw.data-dir=../data' `
        '-Ddebug=false'
    $script:exitCode = $LASTEXITCODE
} finally {
    if ($envCopied) { Remove-Item -Path '.env' -Force -ErrorAction SilentlyContinue }
    if ($script:exitCode -eq 130 -or $script:exitCode -eq 143) { exit 0 }
}
