#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Upload a protected GitHub App PEM to Amsha SecretVault"
#MISE dir = "{{config_root}}"
$ErrorActionPreference = 'Stop'

function Resolve-PythonCommand {
    $candidates = if ($IsWindows) {
        @('python', 'py', 'python3')
    } else {
        @('python3', 'python')
    }

    foreach ($cmd in $candidates) {
        $found = Get-Command $cmd -ErrorAction SilentlyContinue
        if ($found) {
            if ($IsWindows -and $found.Source -match 'Microsoft\\WindowsApps') {
                continue
            }
            try {
                $ver = & $cmd --version 2>&1
                if ($LASTEXITCODE -eq 0 -and $ver -match 'Python 3\.') {
                    return $cmd
                }
            } catch {
                # continue searching
            }
        }
    }

    Write-Host '❌ Python 3 was not found or is blocked by Windows App Execution Aliases.' -ForegroundColor Red
    Write-Host '   AmshaAgent build tasks require Python 3 to format code and run helper scripts.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '   👉 How to fix this on Windows:' -ForegroundColor Cyan
    Write-Host '      1. Open Windows Settings (Win + I) -> Apps -> Advanced app settings -> App execution aliases.' -ForegroundColor Cyan
    Write-Host '      2. Toggle OFF 'python.exe' and 'python3.exe' under 'App Installer'.' -ForegroundColor Cyan
    Write-Host '      3. If Python 3 is installed (e.g. C:\Python313), ensure its directory is in PATH.' -ForegroundColor Cyan
    Write-Host '      4. Or install via Scoop: 'scoop install python'.' -ForegroundColor Cyan
    Write-Host ''
    throw 'Python 3 runtime is required but unavailable.'
}


$scriptPath = Join-Path (Get-Location) 'scripts/upload_github_app_credential.py'
$pythonCommand = Resolve-PythonCommand

& $pythonCommand $scriptPath @args
exit $LASTEXITCODE
