#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Generate source archive (7z). Usage: AMSHA_7Z_PASS=secret mise run utils:7z-source"
$ErrorActionPreference = 'Stop'

$argPass = $env:AMSHA_7Z_PASS
if ([string]::IsNullOrWhiteSpace($argPass)) {
    Write-Host "❌ ERROR: No password provided!" -ForegroundColor Red
    Write-Host "Usage: AMSHA_7Z_PASS=secret mise run utils:7z-source"
    exit 1
}

# ── Auto-install 7z if not found ──────────────────────────────────────────────
$has7z = if ($IsMacOS)      { (Get-Command '7zz' -ErrorAction SilentlyContinue) -or
                               (Get-Command '7z'  -ErrorAction SilentlyContinue) }
         elseif ($IsWindows) { (Get-Command '7z'  -ErrorAction SilentlyContinue) -or
                               (Test-Path "C:\Program Files\7-Zip\7z.exe") -or
                               (Test-Path "C:\Program Files (x86)\7-Zip\7z.exe") }
         else                { Get-Command '7z' -ErrorAction SilentlyContinue }

if (-not $has7z) {
    Write-Host '📦  7z not found — installing...'
    if ($IsMacOS) {
        if (Get-Command brew -ErrorAction SilentlyContinue) { brew install sevenzip }
        else { Write-Host '❌  brew not found. Install Homebrew: https://brew.sh'; exit 1 }
    } elseif ($IsWindows) {
        if (Get-Command winget -ErrorAction SilentlyContinue) {
            winget install --id 7zip.7zip -e --silent
            $env:PATH += ";C:\Program Files\7-Zip"
        } else { Write-Host '❌  winget not found. Install 7-Zip: https://www.7-zip.org'; exit 1 }
    } else {
        if      (Get-Command apt-get -ErrorAction SilentlyContinue) { sudo apt-get install -y p7zip-full }
        elseif  (Get-Command dnf     -ErrorAction SilentlyContinue) { sudo dnf install -y p7zip p7zip-plugins }
        else    { Write-Host '❌  No known package manager. Install p7zip manually.'; exit 1 }
    }
    Write-Host '✅  7z installed.'
}

# ── Resolve 7z binary ─────────────────────────────────────────────────────────
$zipPath = if ($IsWindows) {
    $p = (Get-Command '7z' -ErrorAction SilentlyContinue)?.Source
    if ($p) { $p } else {
        @("C:\Program Files\7-Zip\7z.exe","C:\Program Files (x86)\7-Zip\7z.exe") |
            Where-Object { Test-Path $_ } | Select-Object -First 1
    }
} elseif ($IsMacOS) {
    @('7zz','7z') | ForEach-Object { (Get-Command $_ -ErrorAction SilentlyContinue)?.Source } |
        Where-Object { $_ } | Select-Object -First 1
} else {
    (Get-Command '7z' -ErrorAction SilentlyContinue)?.Source
}
if (-not $zipPath) {
    Write-Host '❌  7z binary not found in PATH after install. Open a new shell and retry.'
    exit 1
}

$out = 'amsha-source.7z'

# ── Exclusion lists ───────────────────────────────────────────────────────────
$script:root = (Get-Location).Path

$script:excludeDirs = @(
    'target', 'dist', '.vscode', '.idea', 'out', '.quarkus', 'quarkus-app',
    'workspaces', 'data', 'lib', 'node_modules', 'node', 'npm',
    '.playwright-browsers', 'MyPrompts', 'docs', 'docs-main',
    'ProjectWorkflowDocs', 'bruno-collections',
    'rest-http', 'backups', '.git'
)

$script:excludeExts = @(
    '.md', '.jar', '.exe', '.dll', '.zip', '.7z', '.class', '.log'
)

$script:excludeFiles = @(
    '.gitignore', '.gitattributes', 'build.env',
    'amsha-source.zip', 'amsha-source.7z', '.env'
)

$script:excludeRelPaths = @(
    'app/src/main/resources/META-INF/resources',
    'app/src/main/resources/native',
    '.playwright-driver',
    '.mvn',
    'scratch'
)

$script:includeFiles = @(
    'Architecture_Invariants.md',
    'Flyway-Migration.md'
)

$script:includeRelPaths = @(
    'docs/SEARCH_AND_DIAGNOSTICS_CHEATSHEET.md'
)

function ShouldExclude($item, $relPath) {
    if (-not $item.PSIsContainer) {
        if ($script:includeFiles -contains $item.Name) { return $false }
    }
    foreach ($rp in $script:includeRelPaths) {
        if ($relPath -eq $rp) { return $false }
        if ($item.PSIsContainer -and $rp.StartsWith("$relPath/")) { return $false }
    }
    foreach ($rp in $script:excludeRelPaths) {
        if ($relPath -eq $rp -or $relPath.StartsWith("$rp/")) { return $true }
    }
    foreach ($seg in $relPath.Split('/')) {
        if ($script:excludeDirs -contains $seg) { return $true }
    }
    if (-not $item.PSIsContainer) {
        if ($script:excludeFiles -contains $item.Name)      { return $true }
        if ($script:excludeExts  -contains $item.Extension) { return $true }
    }
    return $false
}

function CopyFiltered($sourceDir, $destDir) {
    Get-ChildItem -Path $sourceDir | ForEach-Object {
        $relPath = $_.FullName.Substring($script:root.Length).TrimStart('\\/').Replace('\','/')
        if (ShouldExclude $_ $relPath) { Write-Verbose "  skip: $relPath"; return }
        if ($_.PSIsContainer) {
            $dest = Join-Path $destDir $_.Name
            New-Item -ItemType Directory -Path $dest -Force | Out-Null
            CopyFiltered $_.FullName $dest
        } else {
            if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
            Copy-Item $_.FullName (Join-Path $destDir $_.Name) -Force
        }
    }
}

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory $stage | Out-Null

try {
    Write-Host "📂  Staging files from: $($script:root)"
    CopyFiltered $script:root $stage

    if (Test-Path $out) { Remove-Item $out -Force }
    Write-Host "📦  Creating encrypted archive: $out"
    & $zipPath a "$out" "$stage/*" "-p$argPass" -mhe=on -mx=5 | Out-Null
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if (Test-Path $out) {
        Write-Host "✅  Archive created: $out" -ForegroundColor Green
        Get-Item $out | Format-Table Name, @{L='Size';E={'{0:N0} KB' -f ($_.Length/1KB)}} -AutoSize
    } else { exit 1 }
} finally {
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
}
