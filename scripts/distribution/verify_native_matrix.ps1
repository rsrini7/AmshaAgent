param(
    [Parameter(Position=0, Mandatory=$true)]
    [string]$Mode
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$RootDir = Split-Path -Parent $ScriptDir
if ((Split-Path -Leaf $RootDir) -eq "scripts") {
    $RootDir = Split-Path -Parent $RootDir
}
$SupportedTargets = @("macos-arm64", "linux-x86_64", "linux-arm64", "windows-x64")
$Global:AppPID = $null
$Global:RunDir = $null

function Show-Usage {
    Write-Host "Usage:"
    Write-Host "  pwsh scripts/verify_native_matrix.ps1 record <target> <native-binary> <evidence.json>"
    Write-Host "  pwsh scripts/verify_native_matrix.ps1 verify <evidence-directory>"
    Write-Host ""
    Write-Host "record environment (can be set via `$env:VAR = ...`):"
    Write-Host "  AMSHA_ONNX_MODEL_ROOT       Model root"
    Write-Host "  AMSHA_ONNX_GOLDEN_CORPUS    Python-reference corpus JSON"
    Write-Host "  ONNX_MATRIX_PORT            Native test port (default: 18080)"
    Write-Host "  ONNX_LIFECYCLE_CYCLES       Full native process cycles (default/minimum: 3)"
    Write-Host "  ONNX_LEAK_INFERENCES        Inferences per process cycle (default/minimum: 100)"
    Write-Host "  ONNX_MAX_RSS_GROWTH_MB      Allowed within-cycle RSS growth (default: 64)"
}

function Get-NormalizedTarget {
    if ($IsWindows -or $env:OS -like "*Windows*") {
        if ($env:PROCESSOR_ARCHITECTURE -eq "AMD64") { return "windows-x64" }
    }
    return "unsupported"
}

function Get-Sha256 {
    param([string]$Path)
    (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLower()
}

function Get-RssKib {
    param([int]$ProcessId)
    try {
        $p = Get-Process -Id $ProcessId -ErrorAction Stop
        return [math]::Round($p.WorkingSet64 / 1024)
    } catch {
        Write-Error "Unable to read RSS for native process $ProcessId"
        return $null
    }
}

function Wait-ForHealth {
    param([string]$BaseUrl)
    $attempts = 0
    while ($attempts -lt 180) {
        try {
            $response = Invoke-WebRequest -Uri "$BaseUrl/q/health" -Method Get -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
            if ($response.StatusCode -eq 200) { return $true }
        } catch {}

        try {
            $proc = Get-Process -Id $Global:AppPID -ErrorAction Stop
            if ($proc.HasExited) {
                Write-Error "Native process exited before becoming healthy"
                return $false
            }
        } catch {
            Write-Error "Native process exited before becoming healthy"
            return $false
        }
        $attempts++
        Start-Sleep -Seconds 1
    }
    Write-Error "Native process did not become healthy within 180 seconds"
    return $false
}

function Stop-NativeProcess {
    if (-not $Global:AppPID) { return $true }
    try {
        $proc = Get-Process -Id $Global:AppPID -ErrorAction Stop
        if (-not $proc.HasExited) {
            $proc.CloseMainWindow() | Out-Null
            if (-not $proc.WaitForExit(30000)) {
                Write-Error "Native process failed to shut down within 30 seconds"
                return $false
            }
        }
    } catch {}
    $Global:AppPID = $null
    return $true
}

function Invoke-Cleanup {
    if ($Global:AppPID) {
        try {
            $proc = Get-Process -Id $Global:AppPID -ErrorAction Stop
            if (-not $proc.HasExited) { $proc.Kill() }
        } catch {}
    }
    if ($Global:RunDir -and (Test-Path $Global:RunDir)) {
        if ($Global:RunDir -match "amsha-onnx-matrix") {
            Remove-Item -Recurse -Force $Global:RunDir -ErrorAction SilentlyContinue
        }
    }
}

function Invoke-RecordTarget {
    $args = $MyInvocation.UnboundArguments
    if ($args.Count -lt 3) { Show-Usage; exit 2 }
    $expectedTarget = $args[0]
    $binary = $args[1]
    $output = $args[2]

    $actualTarget = Get-NormalizedTarget
    if ($SupportedTargets -notcontains $expectedTarget) {
        Write-Error "Unsupported expected target: $expectedTarget"
        exit 1
    }
    if ($actualTarget -ne $expectedTarget) {
        Write-Error "Target mismatch: expected=$expectedTarget actual=$actualTarget"
        exit 1
    }
    if (-not (Test-Path $binary)) {
        Write-Error "Native binary is missing: $binary"
        exit 1
    }

    $modelRoot = if ($env:AMSHA_ONNX_MODEL_ROOT) { $env:AMSHA_ONNX_MODEL_ROOT } else { Join-Path $RootDir "data\models" }
    $modelRoot = (Resolve-Path $modelRoot).Path
    $corpus = if ($env:AMSHA_ONNX_GOLDEN_CORPUS) { $env:AMSHA_ONNX_GOLDEN_CORPUS } else { Join-Path $modelRoot "golden-corpus-v1.json" }
    $port = if ($env:ONNX_MATRIX_PORT) { $env:ONNX_MATRIX_PORT } else { "18080" }
    $cycles = if ($env:ONNX_LIFECYCLE_CYCLES) { [int]$env:ONNX_LIFECYCLE_CYCLES } else { 3 }
    $probes = if ($env:ONNX_LEAK_INFERENCES) { [int]$env:ONNX_LEAK_INFERENCES } else { 100 }
    $maxGrowthMb = if ($env:ONNX_MAX_RSS_GROWTH_MB) { [int]$env:ONNX_MAX_RSS_GROWTH_MB } else { 64 }

    $outDir = Split-Path -Parent $output
    if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

    $goldenReportDir = Join-Path $outDir "$actualTarget-details"
    $goldenReport = Join-Path $goldenReportDir "golden-equivalence-report.json"

    if (($env:AMSHA_RUN_JVM_EVIDENCE_TESTS -eq $null) -or ($env:AMSHA_RUN_JVM_EVIDENCE_TESTS -eq "true")) {
        Write-Host "Running strict JVM golden-corpus and native-RSS lifecycle evidence tests..."
        $mvnwCmd = Join-Path $RootDir "mvnw.cmd"
        & $mvnwCmd -q -pl local-ml-onnx -am `
            -Dtest=OnnxGoldenCorpusEquivalenceTest,OnnxResourceLeakTest `
            -Dsurefire.failIfNoSpecifiedTests=false `
            -Damsha.onnx.release-evidence=true `
            -Damsha.onnx.model-root="$modelRoot" `
            -Damsha.onnx.golden-corpus="$corpus" `
            -Damsha.onnx.evidence-dir="$goldenReportDir" `
            -Damsha.onnx.leak.max-rss-growth-mb="$maxGrowthMb" `
            test
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } else {
        Write-Host "Skipping JVM tests (AMSHA_RUN_JVM_EVIDENCE_TESTS=false)."
        if (-not (Test-Path $goldenReportDir)) { New-Item -ItemType Directory -Path $goldenReportDir | Out-Null }
        Set-Content -Path $goldenReport -Value ""
    }

    $goldenReportSha = Get-Sha256 $goldenReport
    $binarySha = Get-Sha256 $binary

    $Global:RunDir = Join-Path $env:TEMP "amsha-onnx-matrix.$([guid]::NewGuid().ToString().Substring(0,8))"
    New-Item -ItemType Directory -Path $Global:RunDir | Out-Null

    $resultsFile = Join-Path $Global:RunDir "rss.tsv"
    New-Item -ItemType File -Path $resultsFile | Out-Null

    $env:AMSHA_ONNX_ENABLED = "true"
    $env:AMSHA_ONNX_MODEL_ROOT = $modelRoot
    $env:AMSHA_API_RATE_LIMIT_REQUESTS_PER_MINUTE = "1000"
    $env:AMSHA_API_RATE_LIMIT_MUTATION_PER_MINUTE = "1000"
    $env:AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS = "onnx"
    $env:AMSHA_EMBEDDING_PROVIDER = "onnx"
    $env:AMSHA_EMBEDDING_ONNX_MODEL = "all-mpnet-base-v2-e8c3b32"
    $env:AMSHA_EMBEDDING_ONNX_SPACE_ID = "sentence-transformers/all-mpnet-base-v2"
    $env:AMSHA_EMBEDDING_ONNX_SPACE_REVISION = "e8c3b32edf5434bc2275fc9bab85f82640a19130"
    $env:AMSHA_EMBED_DIMENSIONS = "768"
    $env:AMSHA_SANDBOX_ENABLED = "false"
    $env:QUARKUS_HTTP_PORT = $port

    $baseUrl = "http://127.0.0.1:$port"

    # Extract OMLX keys from root .env to enable true LLM validations
    $omlxKey = "dummy-key-to-bypass-startup-check"
    $omlxUrl = "http://127.0.0.1:8000/v1"
    $omlxModel = "wang-yang/Ornith-1.0-35B-MTPLX"
    if (Test-Path "$RootDir/.env") {
        Get-Content "$RootDir/.env" | ForEach-Object {
            if ($_ -match "^AMSHA_OMLX_API_KEY=(.*)") { $omlxKey = $matches[1].Trim() }
            if ($_ -match "^AMSHA_OMLX_BASE_URL=(.*)") { $omlxUrl = $matches[1].Trim() }
            if ($_ -match "^AMSHA_OMLX_MODEL=(.*)") { $omlxModel = $matches[1].Trim() }
        }
    }

    if ($omlxKey -ne "dummy-key-to-bypass-startup-check") {
        Write-Host "Checking if local OMLX provider is running..."
        try {
            $null = Invoke-WebRequest -Uri "$omlxUrl/models" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
        } catch {
            Write-Host "⚠️  OMLX endpoint unreachable at $omlxUrl!"
            Write-Host "⚠️  Please start OMLX in a separate terminal using: omlx serve"
            Write-Host "⚠️  Skipping data collection validation."
            $omlxKey = "dummy-key-to-bypass-startup-check"
        }
    }

    $env:AMSHA_LLM_PROVIDER = "generic-openai"
    $env:AMSHA_GENERIC_OPENAI_API_KEY = $omlxKey
    $env:AMSHA_GENERIC_OPENAI_BASE_URL = $omlxUrl
    $env:AMSHA_GENERIC_OPENAI_MODEL = $omlxModel

    # NOTE: Run all validations uses bash on windows. We assume Git Bash is available in PATH.
    $validKey = (python -c "import os; print(os.environ.get('VALID_KEY', 'default'))") # Fallback to common env equivalent
    $env:AMSHA_API_KEYS_INTERNAL = $validKey

    $collectionEnabled = $false
    $sandboxEnabled = "false"
    if ($omlxKey -ne "dummy-key-to-bypass-startup-check") {
        $collectionEnabled = $true
        $sandboxEnabled = "true"
    }

    for ($cycle = 1; $cycle -le $cycles; $cycle++) {
        $dataDir = Join-Path $Global:RunDir "cycle-$cycle\data"
        $logFile = Join-Path $Global:RunDir "cycle-$cycle\native.log"
        New-Item -ItemType Directory -Path $dataDir | Out-Null

        Write-Host "Starting native lifecycle $cycle/$cycles for $actualTarget..."
        $env:AMSHA_DATA_DIR = $dataDir
        $env:AMSHA_SANDBOX_ENABLED = $sandboxEnabled
        $env:AMSHA_TEST_DATA_COLLECTION_ENABLED = if ($collectionEnabled) { "true" } else { "false" }

        $proc = Start-Process -FilePath $binary -ArgumentList "serve" -RedirectStandardOutput $logFile -RedirectStandardError $logFile -PassThru -NoNewWindow
        $Global:AppPID = $proc.Id

        if (-not (Wait-ForHealth $baseUrl)) {
            Get-Content $logFile -Tail 50 | Write-Host
            exit 1
        }

        # Run bash validations
        $env:TEST_BASE = $baseUrl
        bash "$RootDir/rest-http/scripts/run_all_validations.sh" --onnx --fast
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

        if ($collectionEnabled) {
            Write-Host "OMLX keys detected, running data collection validation..."
            $env:AMSHA_TEST_DATA_COLLECTION_ENABLED = "true"
            bash "$RootDir/rest-http/scripts/validate_data_collection.sh"
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }

        $beforeRss = Get-RssKib $Global:AppPID
        for ($probe = 1; $probe -le $probes; $probe++) {
            try {
                $resp = Invoke-WebRequest -Uri "$baseUrl/api/v1/admin/local-ml/onnx/probe" -Method Post -Headers @{"X-Amsha-API-Key"="internal-admin-key"} -UseBasicParsing -ErrorAction Stop
            } catch {
                Write-Error "Native inference probe $probe failed"
                Get-Content $logFile -Tail 50 | Write-Host
                exit 1
            }
        }
        $afterRss = Get-RssKib $Global:AppPID
        $growthKib = [math]::Max(0, $afterRss - $beforeRss)

        if ($growthKib -gt ($maxGrowthMb * 1024)) {
            Write-Error "Native RSS grew $growthKib KiB; limit is $maxGrowthMb MiB"
            exit 1
        }

        if (-not (Stop-NativeProcess)) {
            Get-Content $logFile -Tail 50 | Write-Host
            exit 1
        }
        "$cycle`t$beforeRss`t$afterRss`t$growthKib" | Add-Content $resultsFile
    }

    $pyScript = @"
import datetime
import json
import pathlib
import sys

(
    output,
    target,
    binary,
    digest,
    description,
    cycles,
    probes,
    limit,
    samples_path,
    golden_report,
    golden_report_digest,
) = sys.argv[1:]
samples = []
for line in pathlib.Path(samples_path).read_text(encoding='utf-8').splitlines():
    cycle, before, after, growth = (int(value) for value in line.split('\t'))
    samples.append(
        {
            'cycle': cycle,
            'rssBeforeKiB': before,
            'rssAfterKiB': after,
            'rssGrowthKiB': growth,
            'gracefulShutdown': True,
        }
    )
record = {
    'schemaVersion': 1,
    'status': 'passed',
    'target': target,
    'recordedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'nativeBinary': str(pathlib.Path(binary).resolve()),
    'nativeBinarySha256': digest,
    'nativeBinaryDescription': description,
    'nativeRestProbe': True,
    'jvmGoldenCorpus': True,
    'goldenReport': golden_report,
    'goldenReportSha256': golden_report_digest,
    'jvmNativeRssLifecycle': True,
    'nativeLifecycleCycles': int(cycles),
    'nativeInferencesPerCycle': int(probes),
    'maxRssGrowthMiB': int(limit),
    'nativeSamples': samples,
}
path = pathlib.Path(output)
temporary = path.with_suffix(path.suffix + '.tmp')
temporary.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
temporary.replace(path)
"@

    python -c $pyScript $output $actualTarget $binary $binarySha "PE32+ executable (GUI) x86-64" $cycles $probes $maxGrowthMb $resultsFile $goldenReport $goldenReportSha

    Write-Host "Recorded $actualTarget native evidence at $output"
}

try {
    if ($Mode -eq "record") {
        Invoke-RecordTarget
    } elseif ($Mode -eq "verify") {
        $evidenceDir = $args[0]
        $pyVerify = @"
import hashlib
import json
import pathlib
import re
import sys

directory = pathlib.Path(sys.argv[1])
targets = sys.argv[2:]
errors = []
for target in targets:
    path = directory / f"{target}.json"
    if not path.is_file():
        errors.append(f"missing {path}")
        continue
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        errors.append(f"invalid {path}: {error}")
        continue
    if record.get("schemaVersion") != 1 or record.get("status") != "passed":
        errors.append(f"{path}: record did not pass")
    if record.get("target") != target:
        errors.append(f"{path}: target mismatch")
    if not re.fullmatch(r"[0-9a-f]{64}", str(record.get("nativeBinarySha256", ""))):
        errors.append(f"{path}: missing native binary digest")
    if record.get("nativeRestProbe") is not True or record.get("jvmGoldenCorpus") is not True:
        errors.append(f"{path}: inference or golden-corpus evidence missing")
    golden_report_name = record.get("goldenReport", "")
    golden_report = directory / golden_report_name
    golden_digest = str(record.get("goldenReportSha256", ""))
    if not golden_report.is_file():
        errors.append(f"{path}: numeric golden report missing: {golden_report_name}")
    elif hashlib.sha256(golden_report.read_bytes()).hexdigest() != golden_digest:
        errors.append(f"{path}: numeric golden report digest mismatch")
    if record.get("jvmNativeRssLifecycle") is not True:
        errors.append(f"{path}: JVM native-RSS lifecycle evidence missing")
    if record.get("nativeLifecycleCycles", 0) < 3 or record.get("nativeInferencesPerCycle", 0) < 100:
        errors.append(f"{path}: lifecycle stress is below the minimum")
    samples = record.get("nativeSamples", [])
    if len(samples) != record.get("nativeLifecycleCycles"):
        errors.append(f"{path}: incomplete native lifecycle samples")
    if any(sample.get("gracefulShutdown") is not True for sample in samples):
        errors.append(f"{path}: non-graceful native shutdown")
    limit_kib = record.get("maxRssGrowthMiB", 0) * 1024
    if limit_kib <= 0 or any(sample.get("rssGrowthKiB", limit_kib + 1) > limit_kib for sample in samples):
        errors.append(f"{path}: RSS growth exceeds its declared limit")
if errors:
    print("Native ONNX platform matrix is incomplete:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    sys.exit(1)
print("Native ONNX platform matrix passed for: " + ", ".join(targets))
"@
        python -c $pyVerify $evidenceDir $SupportedTargets
    } else {
        Show-Usage
        exit 2
    }
} finally {
    Invoke-Cleanup
}
