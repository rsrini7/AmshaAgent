#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Fetch SonarQube issues in LLM-friendly format"
# Fetches SonarQube issues in LLM-friendly format.
# Usage: mise run sonar:issues                          # all BLOCKER + CRITICAL
#        mise run sonar:issues -- -severity BLOCKER     # BLOCKER only
#        mise run sonar:issues -component arcclaw-core  # one module
#        mise run sonar:issues -resolved                # include resolved issues
$ErrorActionPreference = 'Stop'

$sonarUrl = if ($env:SONAR_API_URL) { $env:SONAR_API_URL } else { 'http://localhost:9000' }

# Load SONAR_TOKEN from .env if not in environment
if (-not $env:SONAR_TOKEN) {
    $envFile = Join-Path $PSScriptRoot '../../../.env'
    if (Test-Path $envFile) {
        Get-Content $envFile | ForEach-Object {
            if ($_ -match '^SONAR_TOKEN=(.+)$') {
                $env:SONAR_TOKEN = $Matches[1].Trim()
            }
        }
    }
}

if (-not $env:SONAR_TOKEN) {
    Write-Host 'ERROR: SONAR_TOKEN not set. Add to .env or export it.'
    exit 1
}

# Parse arguments (handles both "-severity BLOCKER" and "-severity=BLOCKER")
$severity = 'BLOCKER,CRITICAL'
$status = 'OPEN,CONFIRMED'
$component = ''
$resolved = $false
$limit = 200

$i = 0
$argList = $args
while ($i -lt $argList.Count) {
    $a = $argList[$i]
    switch -Regex ($a) {
        '^-severity$'  { $i++; $severity = $argList[$i]; break }
        '^-severity=(.+)$' { $severity = $Matches[1]; break }
        '^-status$'    { $i++; $status = $argList[$i]; break }
        '^-status=(.+)$' { $status = $Matches[1]; break }
        '^-component$' { $i++; $component = $argList[$i]; break }
        '^-component=(.+)$' { $component = $Matches[1]; break }
        '^-resolved$'  { $resolved = $true; break }
        '^-limit$'     { $i++; $limit = [int]$argList[$i]; break }
        '^-limit=(\d+)$' { $limit = [int]$Matches[1]; break }
    }
    $i++
}

if ($resolved) { $status = 'OPEN,CONFIRMED,RESOLVED,CLOSED' }

# Build API URL
if ($component) {
    $url = "$sonarUrl/api/issues/search?componentKeys=$component&severities=$severity&statuses=$status&ps=$limit"
} else {
    $url = "$sonarUrl/api/issues/search?componentKeys=arcclaw&severities=$severity&statuses=$status&ps=$limit"
}

# Fetch issues from SonarQube API
try {
    $token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$($env:SONAR_TOKEN):"))
    $response = Invoke-RestMethod -Uri $url -Headers @{ Authorization = "Basic $token" }
} catch {
    Write-Host "ERROR: Failed to fetch issues from SonarQube: $_"
    exit 1
}

$issues = $response.issues
$total = $response.total

if ($issues.Count -eq 0) {
    Write-Host "No issues found matching criteria (severity=$severity, status=$status)."
    exit 0
}

# Group by severity
$grouped = $issues | Group-Object -Property severity | Sort-Object @{Expression={
    switch ($_.Name) {
        'BLOCKER'  { 0 }
        'CRITICAL' { 1 }
        'MAJOR'    { 2 }
        'MINOR'    { 3 }
        'INFO'     { 4 }
    }
}}

Write-Host "# SonarQube Issues Report"
Write-Host ""
Write-Host "Total: $total issues (showing $($issues.Count))"
Write-Host "Filters: severity=$severity, status=$status"
Write-Host ""

foreach ($group in $grouped) {
    $sev = $group.Name
    $count = $group.Count
    Write-Host "## $sev ($count)"
    Write-Host ""

    $group.Group | ForEach-Object {
        $comp = $_.component -replace 'arcclaw:', ''
        $line = if ($_.line) { $_.line } else { '?' }
        $rule = $_.rule
        $msg = $_.message
        $effort = if ($_.effort) { $_.effort } else { '' }

        Write-Host "- **[$rule]** $comp`:$line"
        Write-Host "  $msg"
        if ($effort) { Write-Host "  Effort: $effort" }
        Write-Host ""
    }
}

# Summary by rule
Write-Host "## Summary by Rule"
Write-Host ""
$issues | Group-Object -Property rule | Sort-Object Count -Descending | ForEach-Object {
    $rule = $_.Name
    $count = $_.Count
    $sev = ($_.Group | Select-Object -First 1).severity
    Write-Host "- $rule ($sev): $count occurrences"
}
Write-Host ""

# Summary by module
Write-Host "## Summary by Module"
Write-Host ""
$issues | ForEach-Object { $_.component -replace 'arcclaw:', '' } | Group-Object | Sort-Object Count -Descending | ForEach-Object {
    Write-Host "- $($_.Name): $($_.Count) issues"
}
