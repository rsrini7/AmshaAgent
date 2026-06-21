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

$provider = Read-Host "  AMSHA_LLM_PROVIDER (groq, openrouter, gemini, nvidia, openai, ollama) [Default: groq]"
if (-not $provider) { $provider = 'groq' }
Set-EnvLine $envPath 'AMSHA_LLM_PROVIDER' $provider

if ($provider -eq 'groq') {
    do {
        $key = Read-Host "  GROQ API KEY (starts with gsk_)"
        if (-not $key.StartsWith('gsk_')) { Write-Host "  X  Must start with gsk_" }
    } while (-not $key.StartsWith('gsk_'))
    Set-EnvLine $envPath 'AMSHA_LLM_API_KEY' $key
    Clear-EnvLine $envPath 'AMSHA_LLM_BASE_URL'
} elseif ($provider -eq 'gemini') {
    $key = Read-Host "  GEMINI API KEY (starts with AIza)"
    Set-EnvLine $envPath 'AMSHA_LLM_API_KEY' $key
    Set-EnvLine $envPath 'AMSHA_LLM_BASE_URL' 'https://generativelanguage.googleapis.com/v1beta/openai/'
    Set-EnvLine $envPath 'AMSHA_LLM_MODEL_NAME' 'gemini-flash-latest'
} elseif ($provider -eq 'nvidia') {
    $key = Read-Host "  NVIDIA API KEY (starts with nvapi-)"
    Set-EnvLine $envPath 'AMSHA_LLM_API_KEY' $key
    Set-EnvLine $envPath 'AMSHA_LLM_BASE_URL' 'https://integrate.api.nvidia.com/v1'
    Set-EnvLine $envPath 'AMSHA_LLM_MODEL_NAME' 'bytedance/seed-oss-36b-instruct'
} elseif ($provider -eq 'openrouter') {
    do {
        $key = Read-Host "  OPENROUTER API KEY (starts with sk-or-)"
        if (-not $key.StartsWith('sk-or-')) { Write-Host "  X  Must start with sk-or-" }
    } while (-not $key.StartsWith('sk-or-'))
    Set-EnvLine $envPath 'AMSHA_LLM_API_KEY' $key
    Set-EnvLine $envPath 'AMSHA_LLM_BASE_URL' 'https://openrouter.ai/api/v1'
} elseif ($provider -eq 'openai') {
    $key = Read-Host "  OPENAI API KEY (starts with sk-)"
    Set-EnvLine $envPath 'AMSHA_LLM_API_KEY' $key
    Clear-EnvLine $envPath 'AMSHA_LLM_BASE_URL'
} elseif ($provider -eq 'ollama') {
    Write-Host "  i  Ollama selected. No API key needed. Ensure it is running on localhost:11434"
    Set-EnvLine $envPath 'AMSHA_LLM_BASE_URL' 'http://localhost:11434/v1'
}
Write-Host "  OK  LLM Provider configured as $provider."
Write-Host ""

Write-Host "  Embedding provider: jina -- API key required."
$embedKey = Read-Host "  AMSHA_EMBED_API_KEY (starts with jina_)"
Set-EnvLine $envPath 'AMSHA_EMBED_API_KEY' $embedKey
Write-Host "  OK  AMSHA_EMBED_API_KEY set."
Write-Host ""
