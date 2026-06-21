# Headroom Launcher

Headroom proxies compress LLM context windows using semantic layers, reducing token costs by 30–60%. Each provider gets its own proxy instance on a dedicated port.

## Quick Reference

```bash
mise run headroom:start-all       # Start all proxies
mise run headroom:stop-all        # Stop all proxies
mise run headroom:status          # Show version, backend, MPS runtime per proxy
mise run headroom:start-groq      # Start a single provider
```

## Port Map

| Provider | Port | Backend | Target URL |
|---|---|---|---|
| openrouter | 8788 | `--backend openrouter` | — |
| groq | 8789 | openai-compatible | `https://api.groq.com/openai/v1` |
| nvidia | 8790 | openai-compatible | `https://integrate.api.nvidia.com/v1` |
| cerebras | 8791 | openai-compatible | `https://api.cerebras.ai/v1` |
| gemini | 8792 | `--backend gemini` | — |
| openai | 8793 | openai-compatible | `https://api.openai.com/v1` |
| ollama | 8794 | openai-compatible | `http://localhost:11434` |
| omlx | 8795 | openai-compatible | `http://127.0.0.1:8000/v1` |
| generic-openai | 8796 | openai-compatible | `$AMSHA_GENERIC_OPENAI_TARGET_URL` |
| llamacpp | 8797 | openai-compatible | `http://localhost:9000/v1` |

## Architecture

```
.mise/tasks/headroom/
├── headroom-common.ps1    # Shared helper: version pin, MPS detection, Start-HeadroomProxy
├── start-all.ps1          # Starts all providers in sequence
├── start-groq.ps1         # Thin wrapper: dot-sources helper, calls Start-HeadroomProxy
├── start-gemini.ps1       #   ...
├── status.ps1             # Queries /health endpoint + process env per running proxy
├── stop-all.ps1           # Kills all proxies by PID file + port fallback
├── pids/                  # PID files (auto-created)
└── logs/                  # stdout/stderr logs (auto-created)
```

Every `start-*.ps1` is a thin wrapper that dot-sources `headroom-common.ps1` and calls `Start-HeadroomProxy`. The shared helper handles:

- Version pinning
- Apple Silicon MPS auto-detection
- Readiness probe (lsof + /health endpoint)
- PID file management
- Logging

## Version Pinning

The version is pinned in **one place** — `headroom-common.ps1`:

```powershell
$script:DefaultHeadroomVersion = '0.25.0'
```

To upgrade: change that string. `uvx` will auto-download the new version because the cache key includes the version string.

**Override via `.env`** (without editing code):

```bash
HEADROOM_VERSION=0.24.0
```

This takes precedence over the built-in pin.

## Apple Silicon MPS

On Apple Silicon Macs, the launcher automatically:

1. Adds the `[pytorch-mps]` extra to the uvx `--from` string
2. Sets `HEADROOM_EMBEDDER_RUNTIME=pytorch_mps` in the process environment

This offloads the memory embedder from CPU to the Apple GPU (MPS), reducing CPU utilization ~8x under load.

**Override via `.env`**:

```bash
# Disable MPS even on Apple Silicon
HEADROOM_EMBEDDER_RUNTIME=

# Force MPS on non-Apple-Silicon (will warn + fallback if MPS unavailable)
HEADROOM_EMBEDDER_RUNTIME=pytorch_mps
```

**Detection logic** (`Test-HeadroomAppleSilicon`):
- `$IsMacOS` is true AND
- `[System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture` is `Arm64`

## Status Output

`mise run headroom:status` queries each running proxy's `/health` endpoint and process environment:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Headroom Proxy Status
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  ✅ groq  PID 75927  port 8789  uptime 00h 07m 07s
     version: 0.25.0 | ready | backend: groq
     target openai: https://api.groq.com/openai/v1
     runtime: HEADROOM_EMBEDDER_RUNTIME=pytorch_mps

  Running: 1  |  Stopped: 0
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

The `backend` field is derived from the process command line (`--backend` flag) or `OPENAI_TARGET_API_URL` env var — not from the health endpoint's `config.backend` field (which reports the internal default, typically `anthropic`).

## Headroom v0.25.0 Changes

Upgraded from unversioned to `0.25.0`. Key changes relevant to this project:

### Compression Safety Rails (#851)

- **Error-output protection**: Failed tool calls pass through verbatim (better agent recovery)
- **Pipeline circuit breaker**: After 3 consecutive transform failures, passes through untouched for 60s cooldown
- **Library inflation guard**: Reverts when compression inflates tokens

### Net-Cost Cache Mutation Formula (#856/#857)

Replaces the old binary `force_compress_threshold` (0.5) with a break-even calculation:

```
gain = dT * (w + r*(R - 1)) - P_alive * (w - r) * S
```

Where `w=1.25` (cache write cost), `r=0.1` (cache read discount), `R`=expected reads, `P_alive`=cache survival probability, `S`=suffix tokens.

**Note**: This formula only engages on providers with prefix caching (Anthropic, OpenAI). Groq/generic-openai don't support prefix caching, so the formula is not exercised on those proxies.

### Other v0.25.0 Changes

- **#854**: Detects re-served tool results as over-compression waste signal
- **#862**: Probe-based retention scoring of compression events
- **#859**: Gated Markdown-KV compaction formatter (serialization-aware)
- **#766**: Apple-Silicon MPS embedding offload (see above)

### Per-Mode Compression Policy (#851/#856)

| Mode | Max Lossy Ratio | Volatile Threshold | Cache Aligner |
|---|---|---|---|
| PAYG / OAuth | 45% | 128 tokens | enabled |
| Subscription | 25% | 32 tokens | disabled |

Controlled by `HEADROOM_PROXY_AUTH_MODE_POLICY_ENFORCEMENT` env var (default: enabled).

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `HEADROOM_VERSION` | `0.25.0` | Override the pinned uvx package version |
| `HEADROOM_EMBEDDER_RUNTIME` | auto (MPS on Apple Silicon) | Memory embedder backend (`pytorch_mps` or empty) |
| `HEADROOM_CONFIG_DIR` | `.headroom/config` | Headroom config directory |
| `HEADROOM_MODEL_LIMITS` | `.headroom/config/models.json` | Per-model context limits |
| `OPENAI_TARGET_API_URL` | per-provider | Upstream OpenAI-compatible endpoint |
| `AMSHA_GENERIC_OPENAI_TARGET_URL` | — | Required for generic-openai provider |

## Troubleshooting

### Proxy fails to start

Check logs:
```bash
cat .mise/tasks/headroom/logs/<provider>.log   # stdout
cat .mise/tasks/headroom/logs/<provider>.err   # stderr
```

Common causes:
- Port already in use (`lsof -i :<port>`)
- uvx cold start exceeds 60s readiness timeout (first install with `[pytorch-mps]` extra)
- Missing API key for the upstream provider

### Stale PID file

If a proxy crashed without cleanup:
```bash
rm .mise/tasks/headroom/pids/<provider>.pid
mise run headroom:start-<provider>
```

### Backend shows "anthropic" in health endpoint

The health endpoint's `config.backend` reports the internal default backend, not the actual target. Use `mise run headroom:status` to see the correct backend derived from process args/env.

### MPS not activating

Verify Apple Silicon detection:
```bash
pwsh -Command ". .mise/tasks/headroom/headroom-common.ps1; Test-HeadroomAppleSilicon"
# Should return True on Apple Silicon

pwsh -Command ". .mise/tasks/headroom/headroom-common.ps1; Get-HeadroomEmbedderRuntimeEnv"
# Should return 'pytorch_mps' on Apple Silicon
```

Check process env:
```bash
ps eww <PID> | grep HEADROOM_EMBEDDER_RUNTIME
```

## Adding a New Provider

1. Create `start-<name>.ps1`:
   ```powershell
   #!/usr/bin/env pwsh
   #MISE shell = "pwsh -NonInteractive -Command"
   #MISE description = "Start Headroom proxy for <Name> on port <PORT>"

   . "$PSScriptRoot/headroom-common.ps1"
   Start-HeadroomProxy -Provider '<name>' -Port <PORT> -TargetApiUrl '<url>'
   ```

2. Add port entry to `$PortMap` in `status.ps1`
3. Add port to `.env.example` under `# Headroom proxy ports`
4. Add to `start-all.ps1` if it should start with `mise run headroom:start-all`
