#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Check sqlite-vec extension exists for your platform (lib/vec0.dll / .so / .dylib)"
$ext = if ($IsWindows) { './lib/vec0.dll' } elseif ($IsMacOS) { './lib/vec0.dylib' } else { './lib/vec0.so' }
if (Test-Path $ext) {
    Write-Host "✅  sqlite-vec extension found: $ext"
} else {
    Write-Host "❌  Extension not found: $ext"
    Write-Host '    Download from: https://github.com/asg017/sqlite-vec/releases/tag/v0.1.6'
    exit 1
}
