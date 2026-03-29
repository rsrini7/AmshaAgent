param([string]$envPath)

function Set-EnvLine($file, $key, $value) {
    $c = [System.IO.File]::ReadAllText($file)
    if ($c -match "(?m)^$key=.*") { $c = $c -replace "(?m)^$key=.*", "$key=$value" }
    else { $c += "`n$key=$value" }
    [System.IO.File]::WriteAllText($file, $c)
}
function Clear-EnvLine($file, $key) {
    $c = [System.IO.File]::ReadAllText($file)
    $c = $c -replace "(?m)^$key=.*", "# $key="
    [System.IO.File]::WriteAllText($file, $c)
}

$provider = Read-Host "  ARCCLAW_LLM_PROVIDER (groq, openrouter, openai, ollama) [Default: openrouter]"
if (-not $provider) { $provider = 'openrouter' }
Set-EnvLine $envPath 'ARCCLAW_LLM_PROVIDER' $provider

if ($provider -eq 'groq') {
    do {
        $key = Read-Host "  GROQ API KEY (starts with gsk_)"
        if (-not $key.StartsWith('gsk_')) { Write-Host "  X  Must start with gsk_" }
    } while (-not $key.StartsWith('gsk_'))
    Set-EnvLine $envPath 'ARCCLAW_LLM_API_KEY' $key
    Clear-EnvLine $envPath 'ARCCLAW_LLM_BASE_URL'
} elseif ($provider -eq 'openrouter') {
    do {
        $key = Read-Host "  OPENROUTER API KEY (starts with sk-or-)"
        if (-not $key.StartsWith('sk-or-')) { Write-Host "  X  Must start with sk-or-" }
    } while (-not $key.StartsWith('sk-or-'))
    Set-EnvLine $envPath 'ARCCLAW_LLM_API_KEY' $key
    Set-EnvLine $envPath 'ARCCLAW_LLM_BASE_URL' 'https://openrouter.ai/api/v1'
} elseif ($provider -eq 'openai') {
    $key = Read-Host "  OPENAI API KEY (starts with sk-)"
    Set-EnvLine $envPath 'ARCCLAW_LLM_API_KEY' $key
    Clear-EnvLine $envPath 'ARCCLAW_LLM_BASE_URL'
} elseif ($provider -eq 'ollama') {
    Write-Host "  i  Ollama selected. No API key needed. Ensure it is running on localhost:11434"
    Set-EnvLine $envPath 'ARCCLAW_LLM_BASE_URL' 'http://localhost:11434/v1'
}
Write-Host "  OK  LLM Provider configured as $provider."
Write-Host ""

Write-Host "  Embedding provider: jina -- API key required."
$embedKey = Read-Host "  ARCCLAW_EMBED_API_KEY (starts with jina_)"
Set-EnvLine $envPath 'ARCCLAW_EMBED_API_KEY' $embedKey
Write-Host "  OK  ARCCLAW_EMBED_API_KEY set."
Write-Host ""
