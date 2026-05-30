# ArcClaw Configuration Reference

> All environment variables. Set in `.env` file or pass as `-D` flags.

Generate required secrets on Linux/macOS: `openssl rand -base64 32`
Generate on Windows: `[Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))`

---

## Core / LLM

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_LLM_API_KEY` | **yes** | — | LLM provider API key (Groq = `gsk_...`) |
| `ARCCLAW_LLM_PROVIDER` | no | `groq` | LLM provider: groq, openai, ollama, openrouter, claude-code, cerebras, gemini, nvidia, omlx |
| `ARCCLAW_LLM_MODEL` | no | provider default | Model name override |
| `ARCCLAW_LLM_BASE_URL` | no | provider default | Custom API base URL |
| `ARCCLAW_LLM_MAX_TOKENS` | no | `4096` | Max output tokens per LLM call |
| `ARCCLAW_LLM_TEMPERATURE` | no | `0.7` | LLM temperature |
| `ARCCLAW_LLM_MAX_RETRIES` | no | `2` | Max LLM retry attempts |
| `ARCCLAW_COMPACTION_LLM_PROVIDER` | no | `groq` | Compaction-specific LLM provider |
| `ARCCLAW_COMPACTION_LLM_MODEL` | no | `llama-3.1-8b-instant` | Compaction model (should be cheap/fast) |
| `ARCCLAW_INFINITE_LOOP_MODELS_LIST` | no | — | Comma-separated providers for infinite-loop multi-slot routing |
| `ARCCLAW_INFINITE_LOOP_COOLDOWN_BASE_SECONDS` | no | `30` | Base cooldown between provider switches (s) |
| `ARCCLAW_INFINITE_LOOP_COOLDOWN_MAX_SECONDS` | no | `300` | Max cooldown (s) |
| `ARCCLAW_INFINITE_LOOP_TRANSIENT_COOLDOWN_SECONDS` | no | `60` | Transient error cooldown (s) |
| `ARCCLAW_INFINITE_LOOP_MAX_LATENCY_MS` | no | `30000` | Max acceptable latency (ms) |

## Security & Vault

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_VAULT_PEPPER` | **yes** | — | 32+ char random string for Argon2id key derivation |
| `ARCCLAW_AUDIT_HMAC_KEY` | **yes** | — | 32+ char random string for HMAC audit chain |
| `ARCCLAW_API_KEYS_INTERNAL` | no | — | Comma-separated internal API keys for admin endpoints |
| `ARCCLAW_API_KEYS_EXTERNAL` | no | — | Comma-separated external API keys for public endpoints |
| `ARCCLAW_API_RATE_LIMIT_REQUESTS_PER_MINUTE` | no | `20` | Max API requests/min per key |
| `ARCCLAW_API_RATE_LIMIT_MUTATION_PER_MINUTE` | no | `10` | Max mutation requests/min per key |
| `ARCCLAW_PII_MODE` | no | `REDACT` | PII mode: REDACT, BLOCK_AND_NOTIFY, OFF |

## Embedding / RAG

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_RAG_EMBED_PROVIDER` | no | `ollama` | Embedding provider: ollama, openai, jina |
| `ARCCLAW_RAG_EMBED_MODEL` | no | provider default | Embedding model name |
| `ARCCLAW_RAG_EMBED_API_KEY` | no | — | API key for embedding provider |
| `ARCCLAW_RAG_EMBED_BASE_URL` | no | provider default | Custom embedding API base URL |
| `ARCCLAW_RAG_EMBED_VECTOR_DIMENSION` | no | `768` | Embedding vector dimension |
| `ARCCLAW_RAG_VEC_EXTENSION_PATH` | no | `./lib/vec0` | Path to sqlite-vec native extension |
| `ARCCLAW_RAG_TOP_K` | no | `5` | Top-K chunks retrieved per query |
| `ARCCLAW_RAG_MIN_SCORE` | no | `0.5` | Minimum cosine similarity score |
| `ARCCLAW_MEMORY_TIERED_BACKEND` | no | `sqlite` | Tiered memory backend: sqlite, postgres, fs |

## Sandbox

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_SANDBOX_STRATEGY` | no | `podman` | podman, namespace, direct, none |
| `ARCCLAW_SANDBOX_IMAGE` | no | `arcclaw-sandbox` | Podman container image |
| `ARCCLAW_SANDBOX_TIMEOUT_SECONDS` | no | `120` | Execution timeout per call |
| `ARCCLAW_SANDBOX_MAX_MEMORY_MB` | no | `512` | Container memory limit (MB) |
| `ARCCLAW_SANDBOX_ALLOW_INTERNET` | no | `true` | Allow network access from sandbox |
| `ARCCLAW_SANDBOX_ALLOW_INSECURE_FALLBACK` | no | `false` | Fallback to direct execution if Podman unavailable |
| `ARCCLAW_SANDBOX_WORK_DIR` | no | `./sandbox-workspace` | Sandbox working directory |

## Telegram

| Variable | Required | Default | Description |
|---|---|---|---|
| `TELEGRAM_BOT_TOKEN` | for attach | — | Telegram bot token from @BotFather |
| `ARCCLAW_TELEGRAM_ENABLED` | no | `true` | Enable Telegram bot |
| `ARCCLAW_TELEGRAM_ALLOWED_USER_IDS` | for attach | — | Comma-separated numeric Telegram user IDs |
| `ARCCLAW_TELEGRAM_ALLOWED_USERS` | no | — | Comma-separated @usernames |

## Tools & Web Search

| Variable | Required | Default | Description |
|---|---|---|---|
| `BRAVE_API_KEY` | optional | — | Brave Search API key |
| `SERPER_API_KEY` | optional | — | Serper Google Search API key |
| `GITHUB_TOKEN` | optional | — | GitHub personal access token |

## Scheduling & Governance

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_SCHEDULER_ENABLED` | no | `true` | Enable cron scheduler |
| `ARCCLAW_CRON_POLL_INTERVAL_SECONDS` | no | `30` | Cron poll interval (s) |
| `ARCCLAW_DELEGATION_MAX_DEPTH` | no | `3` | Max delegation chain depth |
| `ARCCLAW_HITL_EXEMPT_TOOLS` | no | `schedule_task,manage_schedule` | Tools bypassing HITL in SUPERVISED mode |
| `ARCCLAW_HITL_TIMEOUT_HOURS` | no | `24` | Hours before pending approval auto-denies |
| `ARCCLAW_GOVERNANCE_APPROVAL_TIMEOUT_SECONDS` | no | `300` | Approval timeout (s) |
| `ARCCLAW_GOVERNANCE_BUDGET_USD` | no | `0.10` | Per-session USD budget cap |
| `ARCCLAW_GOVERNANCE_BUDGET_WARNING_THRESHOLD` | no | `0.80` | Fraction of budget before warning |
| `ARCCLAW_GOVERNANCE_AUTONOMY_LEVEL` | no | `SUPERVISED` | Default autonomy: FULL, SUPERVISED, READONLY |
| `ARCCLAW_AGENT_AUTO_HEAL_ENABLED` | no | `true` | Auto-heal sandbox failures via Shiva |
| `ARCCLAW_AGENT_AUTO_HEAL_MAX_ATTEMPTS` | no | `2` | Max auto-heal attempts |
| `ARCCLAW_AGENT_ESCALATION_ENABLED` | no | `true` | Enable persona escalation |
| `ARCCLAW_AGENT_ESCALATION_MAX_DEPTH` | no | `2` | Max escalation chain depth |

## Data & Storage

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_DATA_DIR` | no | `./arcclaw-data` | Root data directory |
| `ARCCLAW_SESSION_IDLE_TIMEOUT_MINUTES` | no | `30` | Session idle timeout (min) |
| `ARCCLAW_MEMORY_COMPACTION_THRESHOLD_MESSAGES` | no | `15` | Messages before compaction triggers |
| `ARCCLAW_MEMORY_COMPACTION_THRESHOLD_TOKENS` | no | `6000` | Tokens before compaction triggers |

## Web Channel

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_CHANNEL_WEB_ENABLED` | no | `true` | Enable WebSocket channel |
| `ARCCLAW_CHANNEL_WEB_REQUIRE_AUTH` | no | `false` | Require auth for WebSocket |

## MCP

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_MCP_HEALTH_CHECK_INTERVAL_MS` | no | `15000` | MCP health check interval (ms) |

## Tool Bulkheads

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_TOOLS_BULKHEAD_NETWORK_MAX_ASYNC` | no | `8` | Max parallel network tools |
| `ARCCLAW_TOOLS_BULKHEAD_COMPUTE_MAX_ASYNC` | no | `4` | Max parallel compute tools |
| `ARCCLAW_TOOLS_BULKHEAD_DATABASE_MAX_ASYNC` | no | `6` | Max parallel database tools |
| `ARCCLAW_TOOLS_BULKHEAD_INTERNAL_MAX_ASYNC` | no | `20` | Max parallel internal tools |

## Parallel Tool Execution

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_PARALLEL_TOOL_TIMEOUT_SECONDS` | no | `60` | Parallel tool timeout (s) |

## Resumption

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_RESUMPTION_STALE_MARK_SECONDS` | no | `60` | Resume mark staleness (s) |
| `ARCCLAW_RESUME_LOCK_DB_ADVISORY` | no | `false` | Use DB advisory locks for resume |

## Playwright MCP

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_ENABLED` | no | `false` | Enable Playwright MCP Server mode |
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_HEADLESS` | no | `true` | Headless browser mode |
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_COMMAND` | no | `npx` | MCP server launch command |
| `ARCCLAW_TOOLS_PLAYWRIGHT_MCP_ARGS` | no | `@playwright/mcp` | MCP server args |

## Observability

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_PHOENIX_ENABLED` | no | `true` | Enable OTel trace export to Arize Phoenix |
| `ARCCLAW_PHOENIX_ENDPOINT` | no | `http://localhost:4318/v1/traces` | Phoenix OTLP HTTP endpoint |

## Notification

| Variable | Required | Default | Description |
|---|---|---|---|
| `ARCCLAW_NOTIFICATION_RETRY_ATTEMPTS` | no | `3` | Notification retry attempts |
| `ARCCLAW_NOTIFICATION_RETRY_INITIAL_DELAY_MS` | no | `1000` | Notification retry delay (ms) |
