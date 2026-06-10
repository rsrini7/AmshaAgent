#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Show Headroom proxy stats for Cerebras"

Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
Write-Host "  Headroom Proxy Stats — Cerebras (port 8791)"
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

try {
    $r = Invoke-RestMethod -Uri "http://localhost:8791/stats" -Method GET -ErrorAction Stop
    Write-Host ""
    Write-Host "  Requests:"
    Write-Host "    Total:     $($r.requests.total)"
    Write-Host "    Cached:    $($r.requests.cached)"
    Write-Host "    Failed:    $($r.requests.failed)"
    Write-Host ""
    Write-Host "  Tokens:"
    Write-Host "    Input:     $($r.tokens.input)"
    Write-Host "    Output:    $($r.tokens.output)"
    Write-Host "    Saved:     $($r.tokens.saved)"
    Write-Host "    Savings %: $($r.tokens.savings_percent)%"
    Write-Host ""
    if ($r.savings.by_layer.compression.avg_compression_pct -gt 0) {
        Write-Host "  Compression:"
        Write-Host "    Compressed: $($r.summary.compression.requests_compressed)"
        Write-Host "    Avg:        $($r.savings.by_layer.compression.avg_compression_pct)%"
        Write-Host "    Best:       $($r.savings.by_layer.compression.best_compression_pct)%"
    }
} catch {
    Write-Host "  ❌ Proxy not responding on port 8791"
}
Write-Host ""
