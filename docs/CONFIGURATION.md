# ArcClaw Configuration Reference

All environment variables. Set in `.env` file or pass as `-D` flags at runtime.

Generate secrets: `openssl rand -base64 32` (Linux/macOS) or `[Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))` (Windows).

---

## Core / LLM

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_LLM_API_KEY` | — | LLM provider API key (Groq = `gsk_...`) |
| `ARCCLAW_LLM_PROVIDER` | `groq` | Provider: groq, openai, ollama, openrouter, claude-code, cerebras, gemini, nvidia, omlx |
| `ARCCLAW_LLM_MAX_OUTPUT_TOKENS` | `8192` | Max output tokens per LLM call |
| `ARCCLAW_LLM_MAX_RETRIES` | `3` | Max LLM retry attempts |
| `ARCCLAW_LLM_TEMPERATURE` | `0.1` | LLM temperature |
| `ARCCLAW_LLM_TIMEOUT` | `PT120S` | LLM call timeout (ISO-8601 duration) |
| `ARCCLAW_LLM_CONTEXT_WINDOW` | `128000` | Context window size (tokens) |
| `ARCCLAW_LLM_LOG_HTTP` | `false` | Enable HTTP-level logging for LLM calls |
| `ARCCLAW_LLM_COMPACT_PROMPT_ENABLED` | `false` | Use compact prompt format |
| `ARCCLAW_LLM_STRUCTURED_OUTPUT_MODE` | `NATIVE` | Structured output mode (NATIVE/STRICT/LENIENT) |
| `ARCCLAW_LLM_VAULT_PRIORITY` | `false` | Vault operations get priority LLM access |

## Compaction

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_COMPACTION_PROVIDER` | `groq` | Compaction-specific LLM provider (should be cheap/fast) |
| `ARCCLAW_COMPACTION_MODEL` | `llama-3.1-8b-instant` | Compaction model name |
| `ARCCLAW_COMPACTION_BASE_URL` | `https://api.groq.com/openai/v1` | Compaction API base URL |
| `ARCCLAW_COMPACTION_API_KEY` | — | Compaction API key (falls back to LLM key if unset) |
| `ARCCLAW_COMPACTION_MAX_RETRIES` | `3` | Max compaction retries |
| `ARCCLAW_COMPACTION_OLLAMA_MODEL` | `mistral-small:latest` | Ollama model for compaction |
| `ARCCLAW_COMPACTION_STRUCTURED_OUTPUT_MODE` | `NATIVE` | Compaction structured output mode |

## Intent Classification

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_INTENT_CLASSIFIER_PROVIDER` | `groq` | Intent classifier provider |
| `ARCCLAW_INTENT_CLASSIFIER_MODEL` | `llama-3.1-8b-instant` | Intent classifier model |
| `ARCCLAW_INTENT_CLASSIFIER_BASE_URL` | `https://api.groq.com/openai/v1` | Intent classifier API base URL |
| `ARCCLAW_INTENT_CLASSIFIER_API_KEY` | `not-configured` | Intent classifier API key |
| `ARCCLAW_INTENT_CLASSIFIER_MAX_RETRIES` | `3` | Max intent classification retries |
| `ARCCLAW_INTENT_CLASSIFIER_OLLAMA_MODEL` | `mistral-small:latest` | Ollama model for intent |
| `ARCCLAW_INTENT_CLASSIFIER_STRUCTURED_OUTPUT_MODE` | `NATIVE` | Intent classifier structured output mode |

## Agent Loop

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_AGENT_MAX_RATELIMIT_RETRIES` | `3` | Max rate-limit retries per LLM call |
| `ARCCLAW_AGENT_RATE_LIMIT_DEFAULT_RETRY_SECONDS` | `10` | Default rate-limit retry delay (s) |
| `ARCCLAW_AGENT_RATE_LIMIT_MAX_AUTO_RETRY_SECONDS` | `15` | Max auto retry on rate-limit (s) |
| `ARCCLAW_AGENT_SCHEMA_CORRECTION_MAX_RETRIES_LENIENT` | `6` | Schema correction retries (lenient mode) |
| `ARCCLAW_AGENT_SCHEMA_CORRECTION_MAX_RETRIES_STRICT` | `4` | Schema correction retries (strict mode) |
| `ARCCLAW_DELEGATION_MAX_DEPTH` | `3` | Max delegation chain depth |

## Infinite Loop (Multi-Provider Failover)

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_INFINITE_LOOP_MODELS_LIST` | `omlx,groq,openrouter` | Comma-separated providers for multi-slot routing |
| `ARCCLAW_INFINITE_LOOP_COOLDOWN_BASE_SECONDS` | `30` | Base cooldown between provider switches (s) |
| `ARCCLAW_INFINITE_LOOP_COOLDOWN_MAX_SECONDS` | `300` | Max cooldown (s) |
| `ARCCLAW_INFINITE_LOOP_COOLDOWN_TRANSIENT_SECONDS` | `5` | Transient error cooldown (s) |
| `ARCCLAW_INFINITE_LOOP_MAX_LATENCY_MS` | `60000` | Max acceptable latency (ms) |

## Security & Vault

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_VAULT_PEPPER` | `dev-pepper-...` | 32+ char random string for Argon2id key derivation (**change for production**) |
| `ARCCLAW_AUDIT_HMAC_KEY` | `dev-audit-hmac-key-...` | 32+ char random string for HMAC audit chain (**change for production**) |
| `ARCCLAW_PII_MODE` | `REDACT_AND_FORWARD` | PII mode: REDACT_AND_FORWARD, BLOCK_AND_NOTIFY, OFF |
| `ARCCLAW_AUTO_SEED_SECRETS` | `false` | Auto-seed development secrets on startup |

## Embedding

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_EMBED_PROVIDER` | `ollama` | Embedding provider: ollama, openai, jina |
| `ARCCLAW_EMBED_MODEL` | `nomic-embed-text:v1.5` | Embedding model name |
| `ARCCLAW_EMBED_BASE_URL` | `http://localhost:11434` | Embedding API base URL |
| `ARCCLAW_EMBED_API_KEY` | — | Embedding API key |
| `ARCCLAW_EMBED_DIMENSIONS` | `768` | Embedding vector dimension |

## Memory & RAG

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_MEMORY_TIERED_BACKEND` | `sqlite` | Tiered memory backend: sqlite, postgres, fs |
| `ARCCLAW_MEMORY_TIERED_ENABLED` | `false` | Enable tiered memory system |
| `ARCCLAW_MEMORY_RECALL_ENABLED` | `false` | Enable recall memory (conversation search) |
| `ARCCLAW_MEMORY_RECALL_MAX_SEARCH_RESULTS` | `10` | Max recall search results |
| `ARCCLAW_MEMORY_RECALL_RETENTION_DAYS` | `90` | Recall memory retention (days) |
| `ARCCLAW_MEMORY_RECALL_CROSS_SESSION` | `true` | Allow cross-session recall |
| `ARCCLAW_MEMORY_ARCHIVAL_ENABLED` | `false` | Enable archival memory (long-term RAG) |
| `ARCCLAW_MEMORY_ARCHIVAL_MAX_SEARCH_RESULTS` | `8` | Max archival search results |
| `ARCCLAW_MEMORY_ARCHIVAL_CONFIDENCE_THRESHOLD` | `0.6` | Archival search confidence threshold |
| `ARCCLAW_MEMORY_ARCHIVAL_LOW_CONFIDENCE_THRESHOLD` | `0.5` | Low confidence threshold for archival |
| `ARCCLAW_MEMORY_ARCHIVAL_LOW_CONFIDENCE_DAYS` | `60` | Low confidence archival retention (days) |
| `ARCCLAW_MEMORY_ARCHIVAL_LRU_MAX_AGE_DAYS` | `90` | LRU max age for archival (days) |
| `ARCCLAW_MEMORY_ARCHIVAL_LRU_MIN_ACCESS_COUNT` | `1` | LRU min access count |
| `ARCCLAW_MEMORY_ARCHIVAL_HYBRID_K` | `60` | Hybrid search k value (RRF) |
| `ARCCLAW_MEMORY_CORE_ENABLED` | `false` | Enable core memory (per-user persistent facts) |
| `ARCCLAW_MEMORY_CORE_MAX_BLOCKS_PER_USER` | `20` | Max core memory blocks per user |
| `ARCCLAW_MEMORY_CORE_MAX_CHARS_PER_BLOCK` | `2000` | Max chars per core memory block |
| `ARCCLAW_MEMORY_COMPACTION_MAX_BACKOFF_MS` | `60000` | Max compaction backoff (ms) |
| `ARCCLAW_MEMORY_GOVERNANCE_ARCHIVAL_WRITES_PER_HOUR` | `10` | Max archival writes per hour |
| `ARCCLAW_MEMORY_GOVERNANCE_CORE_WRITES_PER_MINUTE` | `5` | Max core memory writes per minute |
| `ARCCLAW_MEMORY_PROMPT_BUDGET_TOTAL_TOKENS` | `2500` | Memory prompt budget total tokens |
| `ARCCLAW_MEMORY_PROMPT_BUDGET_COMPACTION_TOKENS` | `1200` | Memory prompt budget for compaction |
| `ARCCLAW_MEMORY_PROMPT_BUDGET_CORE_TOKENS` | `800` | Memory prompt budget for core memory |
| `ARCCLAW_MEMORY_PROMPT_BUDGET_ARCHIVAL_RESULTS` | `3` | Memory prompt budget archival results |
| `ARCCLAW_MEMORY_PROMPT_BUDGET_DIRECTIVES` | `5` | Memory prompt budget directives |
| `ARCCLAW_RAG_VEC_PATH` | `./lib/vec0` | Path to sqlite-vec native extension |

## Sandbox

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_SANDBOX_STRATEGY` | `PODMAN` | Sandbox strategy: PODMAN, namespace, direct, none |
| `ARCCLAW_SANDBOX_ENABLED` | `true` | Enable sandbox |
| `ARCCLAW_SANDBOX_PODMAN_IMAGE` | `localhost/arcclaw-tool-runner:latest` | Podman container image |
| `ARCCLAW_SANDBOX_PODMAN_PATH` | `podman` | Podman binary path |
| `ARCCLAW_SANDBOX_TIMEOUT_SECONDS` | `30` | Execution timeout per call (s) |
| `ARCCLAW_SANDBOX_NETWORK_TIMEOUT_SECONDS` | `60` | Network operation timeout (s) |
| `ARCCLAW_SANDBOX_MEMORY_LIMIT` | `512m` | Container memory limit |
| `ARCCLAW_SANDBOX_CPU_LIMIT` | `0.5` | Container CPU limit (cores) |
| `ARCCLAW_SANDBOX_NETWORK` | `host` | Network mode: host, none |
| `ARCCLAW_SANDBOX_MAX_OUTPUT_CHARS` | `8192` | Max output chars from sandbox |
| `ARCCLAW_SANDBOX_USER` | `sandbox` | Sandbox user |
| `ARCCLAW_SANDBOX_ALLOW_INSECURE_FALLBACK` | `false` | Fallback to direct execution if Podman unavailable |
| `ARCCLAW_SANDBOX_PYTHON_EXECUTABLE` | — | Python executable path (direct mode) |
| `ARCCLAW_SANDBOX_JAVA_EXECUTABLE` | — | Java executable path (direct mode) |
| `ARCCLAW_SANDBOX_GO_EXECUTABLE` | — | Go executable path (direct mode) |

## Budget & Governance

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_BUDGET_LIMIT` | `999.00` | Per-session USD budget cap |
| `ARCCLAW_BUDGET_ACTION` | `READONLY` | Action on budget exceeded: BLOCK, READONLY |
| `ARCCLAW_HITL_EXEMPT_TOOLS` | `schedule_task,manage_schedule` | Tools bypassing HITL in SUPERVISED mode |

## Telegram

| Variable | Default | Description |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | `mock-telegram-token` | Telegram bot token from @BotFather |
| `ARCCLAW_TELEGRAM_ENABLED` | `false` | Enable Telegram bot |
| `ARCCLAW_TELEGRAM_ALLOWED_USER_IDS` | — | Comma-separated numeric Telegram user IDs |
| `ARCCLAW_TELEGRAM_ALLOWED_USERS` | `mock-telegram-user` | Comma-separated @usernames |
| `ARCCLAW_CHANNEL_TELEGRAM_MAX_LENGTH` | `4096` | Max Telegram message length |

## Tools

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_TOOLS_BRAVE_API_KEY` | `none` | Brave Search API key |
| `ARCCLAW_TOOLS_SERPER_API_KEY` | `none` | Serper Google Search API key |
| `ARCCLAW_TOOLS_GITHUB_TOKEN` | — | GitHub personal access token |
| `ARCCLAW_TOOLS_PLAYWRIGHT_ENABLED` | `false` | Enable Playwright CLI tool |
| `ARCCLAW_TOOLS_PLAYWRIGHT_HEADLESS` | `true` | Run Playwright headless |
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_ENABLED` | `false` | Enable Playwright MCP Server |
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_HEADLESS` | `true` | MCP browser headless mode |
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_COMMAND` | `npx` | MCP server launch command |
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_ARGS` | `@playwright/mcp` | MCP server args |
| `ARCCLAW_TOOLS_PLAYWRIGHT_RESUMPTION_STALE_MARK_SECONDS` | `300` | Playwright resume staleness (s) |
| `ARCCLAW_GITHUB_REPOS_PER_PAGE` | `100` | GitHub API results per page |

## Web Channel

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_WEB_ENABLED` | `true` | Enable WebSocket channel |
| `ARCCLAW_WEB_REQUIRE_AUTH` | `false` | Require auth for WebSocket |
| `ARCCLAW_CORS_ORIGINS` | `http://localhost:3000,http://localhost:8080` | Allowed CORS origins |

## Chat Memory

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_CHAT_MEMORY_MAX_MESSAGES` | `20` | Max messages in chat memory before compaction |
| `ARCCLAW_CHAT_MEMORY_RETENTION_DAYS` | `30` | Chat memory retention (days) |

## Audit

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_AUDIT_HMAC_KEY` | `dev-audit-hmac-key-...` | HMAC key for audit chain (**change for production**) |
| `ARCCLAW_AUDIT_IDLE_TIMEOUT_MS` | `10000` | Audit drain idle timeout (ms) |

## API & Auth

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_API_KEYS_INTERNAL` | `key-internal-changeme` | Internal API keys for admin endpoints |
| `ARCCLAW_API_KEYS_CI` | `key-ci-changeme` | CI API key |

## Resumption

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_RESUMPTION_STALE_MARK_SECONDS` | `60` | Resume mark staleness (s) |
| `ARCCLAW_TOOLS_PLAYWRIGHT_RESUMPTION_STALE_MARK_SECONDS` | `300` | Playwright resume staleness (s) |

## Checkpoint

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_CHECKPOINT_STALE_CLAIM_MINUTES` | `2` | Stale checkpoint claim timeout (min) |

## Filesystem Backend

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_FS_DATA_DIR` | `./data/fs` | Filesystem backend data directory |
| `ARCCLAW_FS_LUCENE_INDEX_DIR` | `lucene-index` | Lucene index subdirectory |
| `ARCCLAW_FS_VECTOR_DIMENSION` | `768` | Expected embedding dimension |

## PostgreSQL

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_PG_HOST` | `localhost` | PostgreSQL host |
| `ARCCLAW_PG_PORT` | `5432` | PostgreSQL port |
| `ARCCLAW_PG_DB` | `arcclaw` | PostgreSQL database name |
| `ARCCLAW_PG_USER` | `arcclaw` | PostgreSQL user |
| `ARCCLAW_PG_PASSWORD` | `changeme` | PostgreSQL password |

## Observability

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_PHOENIX_ENABLED` | `true` | Enable OTel trace export to Arize Phoenix |
| `ARCCLAW_PHOENIX_ENDPOINT` | `http://localhost:4318/v1/traces` | Phoenix OTLP HTTP endpoint |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry collector endpoint |
| `OTEL_SDK_DISABLED` | `false` | Disable OpenTelemetry SDK |

## Self Evolution

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_SELF_EVOLVE_ENABLED` | `false` | Enable self-evolution system |
| `ARCCLAW_SELF_EVOLVE_BACKEND` | `sqlite` | Self-evolution storage backend |
| `ARCCLAW_SELF_EVOLVE_BUFFER_SIZE` | `50` | Self-evolution buffer size |
| `ARCCLAW_SELF_EVOLVE_DEFAULT_SEARCH_LIMIT` | `8` | Self-evolution default search limit |
| `ARCCLAW_SELF_EVOLVE_DIRECTIVES_ENABLED` | `true` | Enable self-evolution directives |
| `ARCCLAW_SELF_EVOLVE_FLUSH_IDLE_MS` | `300000` | Self-evolution flush idle (ms) |
| `ARCCLAW_SELF_EVOLVE_FLUSH_MAX_EVENTS` | `12` | Self-evolution max events per flush |
| `ARCCLAW_SELF_EVOLVE_SYNTHESIS_TRIGGER` | `manual` | Self-evolution synthesis trigger |

## Retry

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_RETRY_DEFAULT_DELAY_MS` | `30000` | Default retry delay (ms) |

## Miscellaneous

| Variable | Default | Description |
|---|---|---|
| `ARCCLAW_DATA_DIR` | `./data` | Root data directory |
| `ARCCLAW_ENV` | `development` | Environment name |
| `ARCCLAW_DEFAULT_TIMEZONE` | `Asia/Kolkata` | Default timezone |
| `ARCCLAW_FLYWAY_CLEAN_AT_START` | `true` | Clean DB on startup (dev) |
| `ARCCLAW_CORE_UTIL_JSON_SALVAGE_MAX_LINES` | `20` | Max lines for JSON salvage parser |
| `ARCCLAW_CRON_STARTUP_DELAY_MS` | `5000` | Cron scheduler startup delay (ms) |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama API base URL |
| `OLLAMA_MODEL` | `mistral-small:latest` | Ollama default model |
| `ARCCLAW_OPENROUTER_APP_NAME` | (repo URL) | OpenRouter app name |
| `ARCCLAW_OPENROUTER_SITE_URL` | (repo URL) | OpenRouter site URL |
