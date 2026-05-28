#!/usr/bin/env pwsh
#MISE description = "Dev mode - Telegram daemon with hot reload (requires TELEGRAM_BOT_TOKEN in .env)"
$ErrorActionPreference = 'Stop'

if ($IsWindows -and -not ([System.Management.Automation.PSTypeName]'WinCon').Type) {
    Add-Type -TypeDefinition '
        using System;
        using System.Runtime.InteropServices;
        public class WinCon {
            [DllImport("kernel32.dll")] public static extern IntPtr GetStdHandle(int n);
            [DllImport("kernel32.dll")] public static extern bool SetConsoleMode(IntPtr h, uint m);
        }'
}

$envCopied = $false
try {
    if (-not (Test-Path '.env') -and (Test-Path '../.env')) {
        Copy-Item -Path '../.env' -Destination '.env'
        $envCopied = $true
        Write-Host '[dev:attach] Copied ../.env -> arcclaw-app/.env for this session'
    }

    if (-not $env:TELEGRAM_BOT_TOKEN) {
        Write-Host '❌  TELEGRAM_BOT_TOKEN is not set in .env'
        exit 1
    }

    $mvnw    = if ($IsWindows) { '..\mvnw.cmd' } else { '../mvnw' }
    $jvmArgs = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

    & $mvnw quarkus:dev `
        '--no-transfer-progress' `
        "-Djvm.args=$jvmArgs" `
        '-Dquarkus.args=attach' `
        '-Dquarkus.profile=dev' `
        '-Darcclaw.rag.vec-extension-path=../lib/vec0' `
        '-Darcclaw.data-dir=../data' `
        '-Ddebug=false'
    $script:exitCode = $LASTEXITCODE

} finally {
    Get-Process -Name java -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -eq '' } |
        Stop-Process -Force -ErrorAction SilentlyContinue

    if ($IsWindows) {
        $handle = [WinCon]::GetStdHandle(-10)
        [WinCon]::SetConsoleMode($handle, 0x0007) | Out-Null
    }

    [Console]::TreatControlCAsInput = $false
    $host.UI.RawUI.FlushInputBuffer()

    if ($envCopied) {
        Remove-Item -Path '.env' -Force -ErrorAction SilentlyContinue
        Write-Host '[dev:attach] Cleaned up temporary arcclaw-app/.env'
    }

    Write-Host '[dev:attach] Terminal restored. Press Enter if cursor is missing.'
    if ($script:exitCode -eq 130 -or $script:exitCode -eq 143) { exit 0 }
}
