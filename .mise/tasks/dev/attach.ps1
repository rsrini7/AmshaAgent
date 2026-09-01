#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Dev mode - Telegram daemon with hot reload (requires TELEGRAM_BOT_TOKEN in .env)"
#MISE dir = "{{config_root}}/app"
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

try {
    if (-not $env:TELEGRAM_BOT_TOKEN -and -not (Test-Path '../.env')) {
        Write-Host '❌  Root .env is missing; create it before starting Telegram attach mode'
        exit 1
    }

    $mvnw    = if ($IsWindows) { '..\mvnw.cmd' } else { '../mvnw' }
    $jvmArgs = '--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Dsun.misc.unsafe.memory.access=allow -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

    & $mvnw quarkus:dev `
        '--no-transfer-progress' `
        "-Djvm.args=$jvmArgs" `
        '-Dquarkus.args=attach' `
        '-Dquarkus.profile=dev' `
        '-Damsha.rag.vec-extension-path=../lib/vec0' `
        '-Damsha.data-dir=../data' `
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

    Write-Host '[dev:attach] Terminal restored. Press Enter if cursor is missing.'
    if ($script:exitCode -eq 130 -or $script:exitCode -eq 143) { exit 0 }
}
