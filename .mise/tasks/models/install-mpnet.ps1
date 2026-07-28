#!/usr/bin/env pwsh
#MISE shell = "pwsh -NonInteractive -Command"
#MISE description = "Install pinned verified all-mpnet-base-v2 ONNX development bundle"
$ErrorActionPreference = 'Stop'

$bundleId = 'all-mpnet-base-v2-e8c3b32'
$revision = 'e8c3b32edf5434bc2275fc9bab85f82640a19130'
$modelBytes = 435826548L
$modelSha256 = '74187b16d9c946fea252e120cfd7a12c5779d8b8b86838a2e4c56573c47941bd'
$tokenizerBytes = 466021L
$tokenizerSha256 = 'b8be2c30ba5dd723a6d5ee26d013da103d5408d92ddcb23747622f9e48f1d842'
$modelRoot = if ($env:AMSHA_ONNX_MODEL_ROOT) { $env:AMSHA_ONNX_MODEL_ROOT } else { 'data/models' }
$bundleDirectory = Join-Path $modelRoot $bundleId
$sourceBase = "https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/$revision"

function Assert-Artifact([string]$path, [long]$expectedBytes, [string]$expectedSha256) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    $actualBytes = (Get-Item -LiteralPath $path).Length
    $actualSha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    return $actualBytes -eq $expectedBytes -and $actualSha256 -eq $expectedSha256
}

function Install-Artifact([string]$name, [string]$url, [long]$expectedBytes, [string]$expectedSha256) {
    $target = Join-Path $bundleDirectory $name
    if (Assert-Artifact $target $expectedBytes $expectedSha256) {
        Write-Host "✓   Verified $name already installed."
        return
    }
    if (Test-Path -LiteralPath $target) {
        throw "Existing $target does not match the pinned artifact. Delete the development bundle directory and rerun this task."
    }
    $partial = "$target.partial"
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    Write-Host "▶   Downloading pinned $name..."
    Invoke-WebRequest -Uri $url -OutFile $partial -MaximumRedirection 5
    if (-not (Assert-Artifact $partial $expectedBytes $expectedSha256)) {
        Remove-Item -LiteralPath $partial -Force
        throw "Downloaded $name did not match its pinned size and SHA-256."
    }
    Move-Item -LiteralPath $partial -Destination $target
    Write-Host "✓   Verified $name."
}

New-Item -ItemType Directory -Path $bundleDirectory -Force | Out-Null
Install-Artifact 'model.onnx' "$sourceBase/onnx/model.onnx" $modelBytes $modelSha256
Install-Artifact 'tokenizer.json' "$sourceBase/tokenizer.json" $tokenizerBytes $tokenizerSha256

$manifest = [ordered]@{
    bundleId = $bundleId
    revision = $revision
    modelFile = 'model.onnx'
    modelSha256 = $modelSha256
    modelBytes = $modelBytes
    tokenizerFile = 'tokenizer.json'
    tokenizerSha256 = $tokenizerSha256
    tokenizerBytes = $tokenizerBytes
    embedding = [ordered]@{
        tokenizerRevision = $revision
        inputIdsName = 'input_ids'
        attentionMaskName = 'attention_mask'
        tokenTypeIdsName = $null
        outputName = 'last_hidden_state'
        maxSequenceLength = 512
        vectorDimension = 768
        paddingTokenId = 1
        poolingStrategy = 'MASKED_MEAN'
        l2Normalize = $true
        spaceId = 'sentence-transformers/all-mpnet-base-v2'
        spaceRevision = $revision
        warmupProbes = @('Amsha local ONNX warm-up.', 'Vector embedding readiness probe.')
    }
    source = [ordered]@{
        repository = 'https://huggingface.co/sentence-transformers/all-mpnet-base-v2'
        revision = $revision
        modelPath = 'onnx/model.onnx'
        tokenizerPath = 'tokenizer.json'
        license = 'Apache-2.0'
    }
}
$manifestPath = Join-Path $bundleDirectory 'manifest.json'
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
Write-Host "✓   Wrote verified bundle manifest: $manifestPath"
Write-Host "Enable with AMSHA_ONNX_ENABLED=true, AMSHA_EMBEDDING_ONNX_MODEL=$bundleId, and include onnx in AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS."
