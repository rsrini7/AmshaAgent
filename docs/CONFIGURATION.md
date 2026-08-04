# Amsha Agent Configuration Reference

All environment variables. Set in `.env` file or pass as `-D` flags at runtime.

Generate secrets: `openssl rand -base64 32` (Linux/macOS) or `[Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))` (Windows).

---

## Core / LLM

| Variable | Default | Description |
|---|---|---|
| `AMSHA_LLM_API_KEY` | — | LLM provider API key (Groq = `gsk_...`) |
| `AMSHA_LLM_PROVIDER` | `groq` | Provider: `groq`, `openai`, `ollama`, `openrouter`, `claude-code`, `cerebras`, `gemini`, `nvidia`, `omlx`, `generic-openai` |
| `AMSHA_LLM_MAX_OUTPUT_TOKENS` | `8192` | Max output tokens per LLM call |
| `AMSHA_LLM_MAX_RETRIES` | `3` | Max LLM retry attempts |
| `AMSHA_LLM_TEMPERATURE` | `0.1` | LLM temperature |
| `AMSHA_LLM_TIMEOUT` | `PT120S` | LLM call timeout (ISO-8601 duration) |
| `AMSHA_LLM_CONTEXT_WINDOW` | `128000` | Context window size (tokens) |
| `AMSHA_LLM_LOG_HTTP` | `false` | Enable HTTP-level logging for LLM calls |
| `AMSHA_LLM_COMPACT_PROMPT_ENABLED` | `false` | Use compact prompt format |
| `AMSHA_LLM_COMPACT_PROMPT_MAX_CHARS` | `32000` | Max chars for compact prompt format |
| `AMSHA_LLM_STRUCTURED_OUTPUT_MODE` | `NATIVE` | Structured output mode: `NATIVE`, `STRICT`, `LENIENT`, `PROMPT_DIRECTIVE`. `PROMPT_DIRECTIVE` forces JSON-only instructions (use when provider rejects native JSON mode with tools) |
| `AMSHA_LLM_VAULT_PRIORITY` | `false` | Vault operations get priority LLM access |
| `AMSHA_LLM_CIRCUIT_BREAKER_ENABLED` | `true` | Enable per-model circuit breaker isolation |

## Per-Persona / Per-Group Model Routing

Override the default LLM model per agent persona or group (Loka). Resolution order: persona override → group default → global default.

### Persona-Level Override (Finest Grain)

| Variable Pattern | Description |
|---|---|
| `amsha.llm.persona.models.<role>.provider` | Provider for a specific persona (e.g., `brahma`, `shiva`, `vishnu`) |
| `amsha.llm.persona.models.<role>.model` | Model for a specific persona |

**Example:** Brahma uses DeepSeek Coder for code generation:
```bash
amsha.llm.persona.models.brahma.provider=deepseek
amsha.llm.persona.models.brahma.model=deepseek-coder-33b
```

### Group-Level Default (All Members of a Loka)

| Variable Pattern | Description |
|---|---|
| `amsha.llm.group.models.<groupId>.provider` | Provider for all members of a group |
| `amsha.llm.group.models.<groupId>.model` | Model for all members of a group |

**Groups:** `kailasa`, `brahmaloka`, `vaikunta`, `loka-bahir`

**Example:** Kailasa (debugging group) uses fast Llama:
```bash
amsha.llm.group.models.kailasa.provider=groq
amsha.llm.group.models.kailasa.model=meta-llama/llama-4-scout-17b-16e-instruct
```

### Multi-Model Failover

Commas-separated numbered entries for automatic failover:
```bash
amsha.llm.persona.models.brahma.0.provider=deepseek
amsha.llm.persona.models.brahma.0.model=deepseek-coder-33b
amsha.llm.persona.models.brahma.1.provider=groq
amsha.llm.persona.models.brahma.1.model=llama-3.3-70b
```

Circuit breaker per-model isolation is automatic. A failure in one provider (e.g., Groq rate-limit) will NOT trip the circuit for other providers (e.g., OpenAI, Gemini).

### Dynamic Task Routing (SAW Pattern)

To avoid API rate limits and optimize costs, the model router uses a pre-flight classifier (`TaskClassifierService`) to analyze goals:
* **Lightweight Goals**: Tasks classified as chitchat, greetings, or basic commands (like `/reset`) automatically bypass persona/group overrides and route directly to the global default low-cost model, avoiding unnecessary latency and costs on heavy reasoning slots.
* **Heavy/Specialist Goals**: Complex tasks (requiring coding, debugging, security, or architecture) are routed to their designated high-capability models.

## Generic OpenAI Provider

Generic OpenAI-compatible provider for custom endpoints (e.g., DeepSeek,
self-hosted proxies, or any OpenAI API-compatible service). Auto-detected
`deepseek-reasoner` model injects `include_reasoning=true` workaround.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_GENERIC_OPENAI_API_KEY` | — | API key for generic OpenAI provider |
| `AMSHA_GENERIC_OPENAI_MODEL` | — | Model name (e.g., `deepseek-v4-flash`) |
| `AMSHA_GENERIC_OPENAI_BASE_URL` | — | Base URL for generic OpenAI endpoint (e.g., `https://api.example.com/v1` or `http://localhost:8796/v1` via Headroom) |
| `AMSHA_GENERIC_OPENAI_TARGET_URL` | — | Target upstream URL when proxying through Headroom |
| `AMSHA_GENERIC_OPENAI_EXTRA_BODY` | — | Extra JSON body fields (e.g., `{"thinking": {"type": "disabled"}}`) |

Compaction and classifier slots also support `generic-openai`:
`AMSHA_COMPACTION_GENERIC_OPENAI_API_KEY`, `AMSHA_COMPACTION_GENERIC_OPENAI_MODEL`,
`AMSHA_COMPACTION_GENERIC_OPENAI_BASE_URL` and `AMSHA_CLASSIFIER_GENERIC_OPENAI_*`
vars. See Compaction and Intent Classification sections below.

### Local OMLX smoke workflow

The repository smoke runner intentionally sources its endpoint credentials from
`AMSHA_OMLX_API_KEY`, `AMSHA_OMLX_BASE_URL`, and `AMSHA_OMLX_MODEL` in the ignored
root `.env`, with exported process variables taking precedence. Its isolated native
mode maps those values to `AMSHA_GENERIC_OPENAI_*`, sets
`AMSHA_LLM_PROVIDER=generic-openai`, forces the embedding provider to `onnx`, and
disables Telegram; it never selects Ollama. `running` additionally requires
`AMSHA_API_KEYS_INTERNAL` so it can call protected ONNX diagnostics. These are
smoke-runner inputs rather than new Quarkus configuration properties.

```bash
mise run smoke:local-runtime -- omlx
mise run smoke:local-runtime -- running --amsha-base-url http://127.0.0.1:8080
mise run smoke:local-runtime -- native --binary dist/amsha-sqlite
```

Keep all keys in `.env` or the process environment, never in command arguments. The
runner prints only bounded pass/fail labels and never prints headers, prompts,
completions, or raw HTTP responses. See the
[local ONNX guide](./ONNX_LOCAL_ML_GUIDE.md#reusable-local-runtime-smoke-runner).

## Compaction

Compaction uses a multi-slot infinite-loop failover (per-slot provider list). Each
provider in the list has its own slot with independent health tracking.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS` | `groq,ollama` | Comma-separated compaction providers (slot order) |
| `AMSHA_COMPACTION_GROQ_API_KEY` | — | Groq API key for the compaction slot |
| `AMSHA_COMPACTION_GROQ_MODEL` | `llama-3.1-8b-instant` | Compaction model on Groq |
| `AMSHA_COMPACTION_GROQ_BASE_URL` | `https://api.groq.com/openai/v1` | Groq base URL for compaction |
| `AMSHA_COMPACTION_GROQ_MAX_RETRIES` | `3` | Max retries for the Groq compaction slot |
| `AMSHA_COMPACTION_OLLAMA_MODEL` | `mistral-small:latest` | Ollama model for the compaction slot |
| `AMSHA_COMPACTION_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama base URL for compaction |
| `AMSHA_COMPACTION_STRUCTURED_OUTPUT_MODE` | `NATIVE` | Compaction structured output mode: `NATIVE`, `STRICT`, `LENIENT`, `PROMPT_DIRECTIVE` |
| `AMSHA_COMPACTION_AWAIT_TIMEOUT_SECONDS` | `60` | Compaction loop await timeout (s) |

For non-Groq providers, mirror the pattern with `AMSHA_COMPACTION_{PROVIDER}_{KEY}`.
Available providers: `groq`, `openai`, `openrouter`, `cerebras`, `gemini`, `nvidia`,
`omlx`, `ollama`, `generic-openai`. Local providers (`ollama`, `omlx`) do not require an API key.

Generic OpenAI compaction variables:
`AMSHA_COMPACTION_GENERIC_OPENAI_API_KEY`, `AMSHA_COMPACTION_GENERIC_OPENAI_MODEL`,
`AMSHA_COMPACTION_GENERIC_OPENAI_BASE_URL`

## Intent Classification
when all slots are exhausted.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS` | `groq,ollama` | Comma-separated classifier providers (slot order) |
| `AMSHA_CLASSIFIER_GROQ_API_KEY` | — | Groq API key for the classifier slot |
| `AMSHA_CLASSIFIER_GROQ_MODEL` | `llama-3.1-8b-instant` | Classifier model on Groq |
| `AMSHA_CLASSIFIER_GROQ_BASE_URL` | `https://api.groq.com/openai/v1` | Groq base URL for classifier |
| `AMSHA_CLASSIFIER_GROQ_MAX_RETRIES` | `3` | Max retries for the Groq classifier slot |
| `AMSHA_CLASSIFIER_OLLAMA_MODEL` | `mistral-small:latest` | Ollama model for the classifier slot |
| `AMSHA_CLASSIFIER_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama base URL for classifier |
| `AMSHA_INTENT_CLASSIFIER_STRUCTURED_OUTPUT_MODE` | `NATIVE` | Classifier structured output mode: `NATIVE`, `STRICT`, `LENIENT`, `PROMPT_DIRECTIVE` |
| `AMSHA_INTENT_CLASSIFIER_OLLAMA_FALLBACK_ENABLED` | `false` | Enable Ollama fallback for intent classification (avoids latency when Ollama is offline) |

For non-Groq providers, mirror the pattern with `AMSHA_CLASSIFIER_{PROVIDER}_{KEY}`.

## Agent Loop

| Variable | Default | Description |
|---|---|---|
| `AMSHA_AGENT_MAX_RATELIMIT_RETRIES` | `3` | Max rate-limit retries per LLM call |
| `AMSHA_AGENT_RATE_LIMIT_DEFAULT_RETRY_SECONDS` | `10` | Default rate-limit retry delay (s) |
| `AMSHA_AGENT_RATE_LIMIT_MAX_AUTO_RETRY_SECONDS` | `15` | Max auto retry on rate-limit (s) |
| `AMSHA_AGENT_SCHEMA_CORRECTION_MAX_RETRIES_LENIENT` | `6` | Schema correction retries (lenient mode) |
| `AMSHA_AGENT_SCHEMA_CORRECTION_MAX_RETRIES_STRICT` | `4` | Schema correction retries (strict mode) |
| `AMSHA_DELEGATION_MAX_DEPTH` | `3` | Max delegation chain depth |

## Infinite Loop (Multi-Provider Failover)

Each slot (LLM, compaction, classifier, embedding) maintains its own provider
list. Slots rotate through their list, applying cooldown / exponential backoff
on transient errors and permanent disable on auth failures. Every slot shares
the same cooldown timing.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_INFINITE_LOOP_LLM_PROVIDERS` | `omlx,groq,openrouter` | Comma-separated LLM providers (slot order) |
| `AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS` | `groq,ollama` | Comma-separated compaction providers |
| `AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS` | `groq,ollama` | Comma-separated classifier providers |
| `AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS` | `ollama` | Comma-separated embedding providers |
| `AMSHA_INFINITE_LOOP_COOLDOWN_BASE_SECONDS` | `30` | Base cooldown between provider switches (s) |
| `AMSHA_INFINITE_LOOP_COOLDOWN_MAX_SECONDS` | `300` | Max cooldown (s) |
| `AMSHA_INFINITE_LOOP_COOLDOWN_TRANSIENT_SECONDS` | `5` | Transient error cooldown (s) |
| `AMSHA_INFINITE_LOOP_MAX_LATENCY_MS` | `60000` | Max acceptable latency (ms) |

For each provider in `AMSHA_INFINITE_LOOP_LLM_PROVIDERS`, the following per-provider
variables are required (replace `{PROVIDER}` with the provider name, e.g., `GROQ`, `OPENROUTER`, `GEMINI`):
| Variable | Default | Description |
|---|---|---|
| `AMSHA_GROQ_API_KEY` | — | Groq API key for the main LLM slot |
| `AMSHA_GROQ_MODEL` | — | Groq model for the main LLM slot |
| `AMSHA_GEMINI_MODEL` | — | Gemini model for the main LLM slot |
| `AMSHA_OPENROUTER_API_KEY` | — | OpenRouter API key for the main LLM slot |
| `AMSHA_OPENROUTER_MODEL` | — | OpenRouter model for the main LLM slot |

## Headroom

Headroom is an optional LLM context compression proxy (v0.25.0) that sits
between Amsha Agent and remote providers, reducing token costs by 30–60%. Each
provider gets its own proxy instance on a dedicated port. Managed via
`.mise/tasks/headroom/` PowerShell scripts.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_HEADROOM_ENABLED` | `false` | Enable Headroom context compression proxies |
| `AMSHA_HEADROOM_OPENROUTER_PORT` | `8788` | Headroom proxy port for OpenRouter |
| `AMSHA_HEADROOM_GROQ_PORT` | `8789` | Headroom proxy port for Groq |
| `AMSHA_HEADROOM_NVIDIA_PORT` | `8790` | Headroom proxy port for NVIDIA |
| `AMSHA_HEADROOM_CEREBRAS_PORT` | `8791` | Headroom proxy port for Cerebras |
| `AMSHA_HEADROOM_GEMINI_PORT` | `8792` | Headroom proxy port for Gemini |
| `AMSHA_HEADROOM_OPENAI_PORT` | `8793` | Headroom proxy port for OpenAI |
| `AMSHA_HEADROOM_OLLAMA_PORT` | `8794` | Headroom proxy port for Ollama |
| `AMSHA_HEADROOM_OMLX_PORT` | `8795` | Headroom proxy port for OmniMLX |
| `AMSHA_HEADROOM_GENERIC_OPENAI_PORT` | `8796` | Headroom proxy port for Generic OpenAI |
| `AMSHA_HEADROOM_LLAMACPP_PORT` | `8797` | Headroom proxy port for llama.cpp |

See `.mise/tasks/headroom/README.md` for setup and usage.

## Security & Vault

> **Required (no production default):** `AMSHA_VAULT_PEPPER` and `AMSHA_AUDIT_HMAC_KEY` are hard-required at startup (enforced by `CliUtils.validateRequiredEnvVars`). The `dev-...` placeholders shown below apply only to local dev fallbacks; in any real deployment you MUST set them or the app fails to boot. Generating them: `openssl rand -base64 32`.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_VAULT_PEPPER` | `dev-pepper-...` *(dev only)* | **Required.** 32+ char random string for Argon2id key derivation. Never change after first boot — it decrypts stored secrets. |
| `AMSHA_AUDIT_HMAC_KEY` | `dev-audit-hmac-key-...` *(dev only)* | **Required.** 32+ char random string for HMAC audit chain. Never change after first boot — it signs the audit trail. |
| `AMSHA_AUTH_HMAC_KEY` | `dev-auth-hmac-key-...` *(dev only)* | **Required.** 32+ char random string for user session, invite, and API key digests. |
| `AMSHA_AUTH_SESSION_IDLE_MINUTES` | `720` *(dev only)* | Web-session idle timeout in minutes (default 720 = 12h). Bounded 5–1440 (5m–24h). |
| `AMSHA_AUTH_SESSION_ROTATION_GRACE_SECONDS` | `30` *(dev only)* | Seconds a prior session token stays valid after rotation (overlap window for in-flight requests). |
| `AMSHA_AUTH_IDP_OIDC_ENABLED` | `false` | Enable the OIDC external identity provider. |
| `AMSHA_AUTH_IDP_OIDC_CLIENT_ID` | — | OIDC client id. |
| `AMSHA_AUTH_IDP_OIDC_CLIENT_SECRET` | — | OIDC client secret (plaintext; prefer the vault key below). |
| `AMSHA_AUTH_IDP_OIDC_CLIENT_SECRET_VAULT_KEY` | — | Vault system-key name holding the OIDC client secret (preferred over plaintext). |
| `AMSHA_AUTH_IDP_OIDC_AUTHORIZATION_URL` | — | OIDC authorization endpoint. |
| `AMSHA_AUTH_IDP_OIDC_TOKEN_URL` | — | OIDC token endpoint. |
| `AMSHA_AUTH_IDP_OIDC_USERINFO_URL` | — | OIDC userinfo endpoint (used only when no `id_token` is returned). |
| `AMSHA_AUTH_IDP_OIDC_ISSUER` | — | Expected `iss` of the OIDC `id_token` (validated when set). |
| `AMSHA_AUTH_IDP_OIDC_JWKS_URL` | — | OIDC JWKS URL; when set, `id_token` signatures are verified (RS256). |
| `AMSHA_AUTH_IDP_OIDC_REDIRECT_URI` | *(derived)* | Fixed, pre-registered redirect URI sent to the provider (prevents open-redirect). Defaults to the server-derived callback. |
| `AMSHA_AUTH_IDP_GITHUB_ENABLED` | `false` | Enable the GitHub OAuth identity provider. |
| `AMSHA_AUTH_IDP_GITHUB_CLIENT_ID` | — | GitHub OAuth client ID. |
| `AMSHA_AUTH_IDP_GITHUB_CLIENT_SECRET` | — | GitHub OAuth client secret. |
| `AMSHA_AUTH_IDP_GITHUB_REDIRECT_URI` | *(derived)* | Fixed, pre-registered redirect URI sent to GitHub. Defaults to the server-derived callback. |
| `AMSHA_PII_MODE` | `REDACT_AND_FORWARD` | PII mode: REDACT_AND_FORWARD, BLOCK_AND_NOTIFY, OFF |
| `AMSHA_AUTO_SEED_SECRETS` | `false` | Auto-seed development secrets on startup |

## Embedding

Embedding uses a multi-slot infinite-loop failover. Each provider in the list
has its own slot. The vector dimension is shared (set once for the storage
schema) and must match the model used by every active provider.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS` | `ollama` | Comma-separated embedding providers (slot order) |
| `AMSHA_EMBEDDING_OLLAMA_MODEL` | `nomic-embed-text:v1.5` | Ollama embedding model |
| `AMSHA_EMBEDDING_OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama base URL for embedding |
| `AMSHA_EMBEDDING_JINA_API_KEY` | — | Jina AI API key |
| `AMSHA_EMBEDDING_JINA_MODEL` | `jina-embeddings-v5-text-nano` | Jina embedding model |
| `AMSHA_EMBEDDING_JINA_BASE_URL` | `https://api.jina.ai/v1/` | Jina base URL |
| `AMSHA_EMBEDDING_OPENAI_API_KEY` | — | OpenAI API key for embedding |
| `AMSHA_EMBEDDING_OPENAI_MODEL` | `text-embedding-3-small` | OpenAI embedding model |
| `AMSHA_EMBEDDING_OPENAI_BASE_URL` | `https://api.openai.com/v1` | OpenAI base URL for embedding |
| `AMSHA_EMBEDDING_<PROVIDER>_TIMEOUT_SECONDS` | `30` | Per-provider embedding timeout (s) |
| `AMSHA_EMBED_DIMENSIONS` | `768` | Embedding vector dimension (must match active model) |

## Memory & RAG

| Variable | Default | Description |
|---|---|---|
| `AMSHA_PERSISTENCE_BACKEND` | *(unset)* | **Build-time** persistence backend: sqlite (default), postgres, fs. Controls `@IfBuildProperty`. Rebuild after changing. |
| `QUARKUS_FLYWAY_ENABLED` | `true` | **Build-time**. Set to `false` when `AMSHA_PERSISTENCE_BACKEND=fs` (disables SQL migrations and Jdbi-dependent beans). |
| `AMSHA_MEMORY_TIERED_BACKEND` | `sqlite` | Tiered memory backend: sqlite, postgres, fs |
| `AMSHA_MEMORY_TIERED_ENABLED` | `false` | Enable tiered memory system |
| `AMSHA_MEMORY_RECALL_ENABLED` | `false` | Enable recall memory (conversation search) |
| `AMSHA_MEMORY_RECALL_MAX_SEARCH_RESULTS` | `10` | Max recall search results |
| `AMSHA_MEMORY_RECALL_RETENTION_DAYS` | `90` | Recall memory retention (days) |
| `AMSHA_MEMORY_RECALL_CROSS_SESSION` | `true` | Allow cross-session recall |
| `AMSHA_MEMORY_ARCHIVAL_ENABLED` | `false` | Enable archival memory (long-term RAG) |
| `AMSHA_MEMORY_ARCHIVAL_MAX_SEARCH_RESULTS` | `8` | Max archival search results |
| `AMSHA_MEMORY_ARCHIVAL_CONFIDENCE_THRESHOLD` | `0.6` | Archival search confidence threshold |
| `AMSHA_MEMORY_ARCHIVAL_LOW_CONFIDENCE_THRESHOLD` | `0.5` | Low confidence threshold for archival |
| `AMSHA_MEMORY_ARCHIVAL_LOW_CONFIDENCE_DAYS` | `60` | Low confidence archival retention (days) |
| `AMSHA_MEMORY_ARCHIVAL_LRU_MAX_AGE_DAYS` | `90` | LRU max age for archival (days) |
| `AMSHA_MEMORY_ARCHIVAL_LRU_MIN_ACCESS_COUNT` | `1` | LRU min access count |
| `AMSHA_MEMORY_ARCHIVAL_HYBRID_K` | `60` | Hybrid search k value (RRF) |
| `AMSHA_MEMORY_CORE_ENABLED` | `false` | Enable core memory (per-user persistent facts) |
| `AMSHA_MEMORY_CORE_MAX_BLOCKS_PER_USER` | `20` | Max core memory blocks per user |
| `AMSHA_MEMORY_CORE_MAX_CHARS_PER_BLOCK` | `2000` | Max chars per core memory block |
| `AMSHA_MEMORY_COMPACTION_MAX_BACKOFF_MS` | `60000` | Max compaction backoff (ms) |
| `AMSHA_MEMORY_GOVERNANCE_ARCHIVAL_WRITES_PER_HOUR` | `10` | Max archival writes per hour |
| `AMSHA_MEMORY_GOVERNANCE_CORE_WRITES_PER_MINUTE` | `5` | Max core memory writes per minute |
| `AMSHA_MEMORY_PROMPT_BUDGET_TOTAL_TOKENS` | `2500` | Memory prompt budget total tokens |
| `AMSHA_MEMORY_PROMPT_BUDGET_COMPACTION_TOKENS` | `1200` | Memory prompt budget for compaction |
| `AMSHA_MEMORY_PROMPT_BUDGET_CORE_TOKENS` | `800` | Memory prompt budget for core memory |
| `AMSHA_MEMORY_PROMPT_BUDGET_ARCHIVAL_RESULTS` | `3` | Memory prompt budget archival results |
| `AMSHA_MEMORY_PROMPT_BUDGET_DIRECTIVES` | `5` | Memory prompt budget directives |
| `AMSHA_MEMORY_RETENTION_ENABLED` | `true` | Enable memory retention scheduler (daily purges) |
| `AMSHA_MEMORY_RETENTION_RECALL_RETENTION_DAYS` | `30` | Daily purge window for recall messages (days) |
| `AMSHA_MEMORY_RETENTION_ARCHIVAL_EVICTION_THRESHOLD` | `0.3` | Daily purge threshold for low-confidence archival context |
| `AMSHA_MEMORY_RETENTION_ARCHIVAL_MIN_AGE_DAYS` | `30` | Minimum age before low-confidence archival context is purged |
| `AMSHA_SESSION_CATALOG_ARCHIVE_AFTER_DAYS` | `90` | Inactivity threshold (days) before catalog sessions are archived |
| `AMSHA_WORKSPACE_SESSION_TTL_MINUTES` | `30` | Time-to-live in minutes for active sessions since the last heartbeat |
| `AMSHA_RAG_VEC_PATH` | `./lib/vec0` | Path to sqlite-vec native extension |

## In-Process Local ML (ONNX Runtime)

| Variable | Default | Description |
|---|---|---|
| `AMSHA_ONNX_ENABLED` | `false` | Enable in-process ONNX Runtime Java & DJL Tokenizer ML inference |
| `AMSHA_ONNX_MODEL_ROOT` | `data/models` | Root directory storing verified local model bundles (`data/models/`) |
| `AMSHA_ONNX_MAX_MODEL_FILE_BYTES` | `1073741824` | Max file byte limit (1 GB) for verified ONNX model bundle files |
| `AMSHA_ONNX_INFERENCE_THREADS` | `2` | Number of dedicated CPU worker threads for ONNX inference execution |
| `AMSHA_ONNX_QUEUE_CAPACITY` | `64` | Maximum capacity of the bounded CPU inference task queue |
| `AMSHA_ONNX_SHUTDOWN_GRACE` | `PT10S` | Grace period duration for draining in-flight leases before shutdown |
| `AMSHA_ONNX_MAX_INPUT_BYTES` | `262144` | Max UTF-8 byte limit (256 KB) per input text segment |
| `AMSHA_ONNX_MAX_BATCH_INPUT_BYTES` | `1048576` | Max UTF-8 byte limit (1 MB) per batch request |
| `AMSHA_ONNX_MAX_BATCH_SIZE` | `32` | Maximum text segment batch size per embedding request |
| `AMSHA_ONNX_WARMUP_ENABLED` | `true` | Enable bounded probes when the ONNX embedding slot is created; failed warm-up rejects that slot |
| `AMSHA_ONNX_WARMUP_MAX_PROBES` | `5` | Maximum number of probe executions during session warmup |
| `AMSHA_ONNX_WARMUP_TIMEOUT` | `PT3S` | Maximum duration limit for session warmup probing |
| `AMSHA_EMBEDDING_ONNX_MODEL` | *(unset)* | Verified local bundle ID under `AMSHA_ONNX_MODEL_ROOT` (e.g. `all-mpnet-base-v2-e8c3b32`) |
| `AMSHA_EMBEDDING_ONNX_TIMEOUT_SECONDS` | `5` | Request timeout in seconds for ONNX embedding model requests |
| `AMSHA_EMBEDDING_ONNX_SPACE_ID` | *(unset)* | Vector space ID matching bundle manifest (e.g. `sentence-transformers/all-mpnet-base-v2`) |
| `AMSHA_EMBEDDING_ONNX_SPACE_REVISION` | *(unset)* | Vector space revision matching bundle manifest Git commit SHA |

## Self-Evolution

| Variable | Default | Description |
|---|---|---|
| `AMSHA_SELF_EVOLVE_ENABLED` | `false` | Enable behavioral self-evolution and trace capture |
| `AMSHA_SELF_EVOLVE_BACKEND` | `sqlite` | Backend for self-evolve trace/directive storage (sqlite, postgres, fs) |
| `AMSHA_SELF_EVOLVE_BUFFER_SIZE` | `50` | Buffer size for trace event processing |
| `AMSHA_SELF_EVOLVE_DEFAULT_SEARCH_LIMIT` | `8` | Default limit for searching self-evolve directives |
| `AMSHA_SELF_EVOLVE_DIRECTIVES_ENABLED` | `true` | Enable synthesized directive injection in prompt assembly |
| `AMSHA_SELF_EVOLVE_SYNTHESIS_TRIGGER` | `manual` | Synthesis trigger mode (manual, cron) |
| `AMSHA_SELF_EVOLVE_FLUSH_MAX_EVENTS` | `12` | Max events buffered before flushing to persistent trace store |
| `AMSHA_SELF_EVOLVE_FLUSH_IDLE_MS` | `300000` | Buffer idle flush timeout (ms) |

## Sandbox

| Variable | Default | Description |
|---|---|---|
| `AMSHA_SANDBOX_STRATEGY` | `PODMAN` | Sandbox strategy: PODMAN, namespace, direct, none |
| `AMSHA_SANDBOX_ENABLED` | `true` | Enable sandbox |
| `AMSHA_SANDBOX_PODMAN_IMAGE` | `localhost/amsha-tool-runner:latest` | Podman container image |
| `AMSHA_SANDBOX_PODMAN_PATH` | `podman` | Podman binary path |
| `AMSHA_SANDBOX_TIMEOUT_SECONDS` | `30` | Execution timeout per call (s) |
| `AMSHA_SANDBOX_NETWORK_TIMEOUT_SECONDS` | `60` | Network operation timeout (s) |
| `AMSHA_SANDBOX_MEMORY_LIMIT` | `512m` | Container memory limit |
| `AMSHA_SANDBOX_CPU_LIMIT` | `0.5` | Container CPU limit (cores) |
| `AMSHA_SANDBOX_NETWORK` | `host` | Network mode: host, none |
| `AMSHA_SANDBOX_MAX_OUTPUT_CHARS` | `8192` | Max output chars from sandbox |
| `AMSHA_SANDBOX_USER` | `sandbox` | Sandbox user |
| `AMSHA_SANDBOX_ALLOW_INSECURE_FALLBACK` | `false` | Fallback to direct execution if Podman unavailable |
| `AMSHA_SANDBOX_PYTHON_EXECUTABLE` | — | Python executable path (direct mode) |
| `AMSHA_SANDBOX_JAVA_EXECUTABLE` | — | Java executable path (direct mode) |
| `AMSHA_SANDBOX_GO_EXECUTABLE` | — | Go executable path (direct mode) |

## Budget & Governance

| Variable | Default | Description |
|---|---|---|
| `AMSHA_BUDGET_LIMIT` | `999.00` | Per-session USD budget cap |
| `AMSHA_BUDGET_ACTION` | `READONLY` | Action on budget exceeded: BLOCK, READONLY |
| `AMSHA_HITL_EXEMPT_TOOLS` | `schedule_task,manage_schedule` | Tools bypassing HITL in SUPERVISED mode |

## Telegram

| Variable | Default | Description |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | `mock-telegram-token` | Telegram bot token from @BotFather |
| `AMSHA_TELEGRAM_ENABLED` | `false` | Enable Telegram bot |
| `AMSHA_TELEGRAM_ALLOWED_USER_IDS` | — | Comma-separated numeric Telegram user IDs |
| `AMSHA_TELEGRAM_ALLOWED_USERS` | `mock-telegram-user` | Comma-separated @usernames |
| `AMSHA_CHANNEL_TELEGRAM_MAX_LENGTH` | `4096` | Max Telegram message length |

## Tools

| Variable | Default | Description |
|---|---|---|
| `AMSHA_TOOLS_BRAVE_API_KEY` | `none` | Brave Search API key |
| `AMSHA_TOOLS_SERPER_API_KEY` | `none` | Serper Google Search API key |
| `AMSHA_TOOLS_GITHUB_TOKEN` | — | GitHub personal access token |
| `AMSHA_TOOLS_PLAYWRIGHT_ENABLED` | `false` | Enable Playwright CLI tool |
| `AMSHA_TOOLS_PLAYWRIGHT_SKIP_INSTALL` | `false` | Skip Playwright browser installation on startup |
| `AMSHA_TOOLS_PLAYWRIGHT_HEADLESS` | `true` | Run Playwright headless |
| `AMSHA_TOOLS_PLAYWRIGHT_MCP_ENABLED` | `false` | Enable Playwright MCP Server |
| `AMSHA_TOOLS_PLAYWRIGHT_MCP_HEADLESS` | `true` | MCP browser headless mode |
| `AMSHA_TOOLS_PLAYWRIGHT_MCP_COMMAND` | `npx` | MCP server launch command |
| `AMSHA_TOOLS_PLAYWRIGHT_MCP_ARGS` | `@playwright/mcp` | MCP server args |
| `AMSHA_MCP_SERVER_ENABLED` | `true` | Enable the outbound MCP client REST API for connecting to remote servers |
| `AMSHA_MCP_HEALTH_CHECK_INTERVAL_MS` | `15000` | How often to check health of active SSE/HTTP outbound MCP connections (ms) |
| `AMSHA_MCP_PODMAN_HEALTH_CHECK_INTERVAL_MS` | `15000` | How often to check health of active Podman/Stdio outbound MCP connections (ms) |
| `AMSHA_TOOLS_PLAYWRIGHT_RESUMPTION_STALE_MARK_SECONDS` | `300` | Playwright resume staleness (s) |
| `AMSHA_TOOLS_HOST_EXEC_SKILLS` | `jdb-debugger` | Skills allowed to use host_exec tool (comma-separated) |
| `AMSHA_HOST_EXEC_MAX_OUTPUT_CHARS` | `8192` | Max output chars for host_exec tool (falls back to sandbox max) |
| `AMSHA_HOST_EXEC_PYTHON_EXECUTABLE` | — | Python executable path for host_exec (falls back to sandbox python) |
| `AMSHA_HOST_EXEC_JAVA_EXECUTABLE` | — | Java executable path for host_exec |
| `AMSHA_HOST_EXEC_GO_EXECUTABLE` | — | Go executable path for host_exec |

## Skills & SkillsJars

| Variable | Default | Description |
|---|---|---|
| `AMSHA_SKILLSJARS_REQUIRE_APPROVAL_FOR_SENSITIVE` | `true` | When true, sensitive operations requested by third-party SkillsJars (e.g. COMPUTE/NETWORK/DELEGATE capabilities) require human approval. |
| `AMSHA_SKILLS_ADAPTIVE_ENABLED` | `false` | Enable adaptive skill adaptation (dynamic skill loading from JAR files). |
| `AMSHA_SKILLS_DEFAULT_MAX_ITERATIONS` | `15` | Default maximum iterations for skill execution loops. |
| `AMSHA_GITHUB_REPOS_PER_PAGE` | `100` | GitHub API results per page |

## Web Channel

| Variable | Default | Description |
|---|---|---|
| `AMSHA_WEB_ENABLED` | `true` | Enable WebSocket channel |
| `AMSHA_WEB_REQUIRE_AUTH` | `false` | Require auth for WebSocket |
| `AMSHA_CORS_ORIGINS` | `http://localhost:3000,http://localhost:8080` | Allowed CORS origins |

## Chat Memory

| Variable | Default | Description |
|---|---|---|
| `AMSHA_CHAT_MEMORY_MAX_MESSAGES` | `20` | Max messages in chat memory before compaction |
| `AMSHA_CHAT_RECOVERED_OBSERVATION_MAX_CHARS` | `2000` | Max chars kept when recovering a malformed final answer from tool output |
| `AMSHA_CHAT_MEMORY_RETENTION_DAYS` | `30` | Chat memory retention (days) |

## Audit

| Variable | Default | Description |
|---|---|---|
| `AMSHA_AUDIT_HMAC_KEY` | `dev-audit-hmac-key-...` *(dev only)* | **Required.** HMAC key for audit chain (see Security & Vault; no production default) |
| `AMSHA_AUDIT_IDLE_TIMEOUT_MS` | `10000` | Audit drain idle timeout (ms) |

## API & Auth

> **Required (no production default):** `AMSHA_API_KEYS_INTERNAL` and `AMSHA_API_KEYS_CI` are hard-required at startup (enforced by `CliUtils.validateRequiredEnvVars`). Generate: `openssl rand -base64 32`.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_API_KEYS_INTERNAL` | `key-internal-changeme` *(dev only)* | **Required.** Internal API keys for admin endpoints (`Authorization: Bearer <token>`) |
| `AMSHA_API_KEYS_CI` | `key-ci-changeme` *(dev only)* | **Required.** CI API key (read-only) |
| `AMSHA_AUTH_HMAC_KEY` | `dev-auth-hmac-key-...` *(dev only)* | **Required.** HMAC key for session and API key digests |

## Resumption

| Variable | Default | Description |
|---|---|---|
| `AMSHA_RESUMPTION_STALE_MARK_SECONDS` | `60` | Resume mark staleness (s) |
| `AMSHA_TOOLS_PLAYWRIGHT_RESUMPTION_STALE_MARK_SECONDS` | `300` | Playwright resume staleness (s) |

## Checkpoint

| Variable | Default | Description |
|---|---|---|
| `AMSHA_CHECKPOINT_STALE_CLAIM_MINUTES` | `2` | Stale checkpoint claim timeout (min) |

## Filesystem Backend

| Variable | Default | Description |
|---|---|---|
| `AMSHA_FS_DATA_DIR` | `./data/fs` | Filesystem backend data directory |
| `AMSHA_FS_LUCENE_INDEX_DIR` | `lucene-index` | Lucene index subdirectory |
| `AMSHA_FS_VECTOR_DIMENSION` | `768` | Expected embedding dimension |

## PostgreSQL

| Variable | Default | Description |
|---|---|---|
| `AMSHA_PG_HOST` | `localhost` | PostgreSQL host |
| `AMSHA_PG_PORT` | `5432` | PostgreSQL port |
| `AMSHA_PG_DB` | `amsha` | PostgreSQL database name |
| `AMSHA_PG_USER` | `amsha` | PostgreSQL user |
| `AMSHA_PG_PASSWORD` | `changeme` | PostgreSQL password |

## Observability

| Variable | Default | Description |
|---|---|---|
| `AMSHA_PHOENIX_ENABLED` | `true` | Enable OTel trace export to Arize Phoenix |
| `AMSHA_PHOENIX_ENDPOINT` | `http://localhost:4318/v1/traces` | Phoenix OTLP HTTP endpoint |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry collector endpoint |
| `OTEL_SDK_DISABLED` | `false` | Disable OpenTelemetry SDK |

## Retry

| Variable | Default | Description |
|---|---|---|
| `AMSHA_RETRY_DEFAULT_DELAY_MS` | `30000` | Default retry delay (ms) |

## Miscellaneous

| Variable | Default | Description |
|---|---|---|
| `AMSHA_DATA_DIR` | `./data` | Root data directory |
| `AMSHA_ENV` | `development` | Environment name |
| `AMSHA_DEFAULT_TIMEZONE` | `Asia/Kolkata` | Default timezone |
| `AMSHA_FLYWAY_CLEAN_AT_START` | `true` | Clean DB on startup (dev) |
| `AMSHA_CORE_UTIL_JSON_SALVAGE_MAX_LINES` | `20` | Max lines for JSON salvage parser |
| `AMSHA_CRON_STARTUP_DELAY_MS` | `5000` | Cron scheduler startup delay (ms) |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama API base URL |
| `OLLAMA_MODEL` | `mistral-small:latest` | Ollama default model |
| `AMSHA_OPENROUTER_APP_NAME` | (repo URL) | OpenRouter app name |
| `AMSHA_OPENROUTER_SITE_URL` | (repo URL) | OpenRouter site URL |

## Interactive `.env` changes

The first-run setup only changes `.env` through the interactive wizard. Each actual update is reported on stderr with the absolute path, key name, and redacted state transition (`<unset>`, `<set>`, or `disabled`); secret values are never printed. The same records are appended to `.env.changes.log` beside `.env`. Choosing `No` for an optional integration stores an explicit `none` opt-out so it is not prompted again. Commenting out a variable makes it unset, so it remains eligible for setup.

## Testing

| Property / Variable | Default | Description |
|---|---|---|
| `amsha.test.force-error` | _(system prop)_ | When running tests, set `-Damsha.test.force-error=true` to force `InfiniteLoopProvider` to fail with real errors instead of injecting dummy fallbacks (`"Dummy response"`). Useful for testing error-handling paths explicitly. |
