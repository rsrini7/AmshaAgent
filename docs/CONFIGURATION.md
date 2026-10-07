# Amsha Agent Configuration Reference

All environment variables. Set in `.env` file or pass as `-D` flags at runtime.

Generate secrets: `openssl rand -base64 32` (Linux/macOS) or `[Convert]::ToBase64String((1..32 | %{[byte](Get-Random -Max 256)}))` (Windows).

---

## Core / LLM

| Variable | Default | Description |
|---|---|---|
| `AMSHA_{PROVIDER}_API_KEY` | — | Provider-specific LLM API key, for example `AMSHA_GROQ_API_KEY` (`gsk_...`) |
| `AMSHA_LLM_PROVIDER` | `groq` in `application.yaml`; the repository `.env.example` explicitly selects `infinite-loop` | Provider ID: `groq`, `openai`, `ollama`, `claude-code`, `openrouter`, `nvidia`, `cerebras`, `sarvam`, `opencode`, `huggingface`, `mistral`, `cloudflare`, `cohere`, `codex`, `agy`, `kimchi`, `foundry`, `gemini`, `omlx`, `llamacpp`, `generic-openai`, or the internal `infinite-loop` provider |
| `AMSHA_LLM_MAX_OUTPUT_TOKENS` | `8192` | Max output tokens per LLM call |
| `AMSHA_LLM_MAX_RETRIES` | `3` | Max LLM retry attempts |
| `AMSHA_LLM_TEMPERATURE` | `0.1` | LLM temperature |
| `AMSHA_LLM_TIMEOUT` | `PT120S` | LLM call timeout (ISO-8601 duration) |
| `AMSHA_LLM_CONTEXT_WINDOW` | `128000` | Context window size (tokens) |
| `AMSHA_LLM_LOG_HTTP` | `false` | Enable HTTP-level logging for LLM calls |
| `AMSHA_LLM_COMPACT_PROMPT_ENABLED` | `false` | Use compact prompt format |
| `AMSHA_LLM_COMPACT_PROMPT_MAX_CHARS` | `32000` | Max chars for compact prompt format |
| `AMSHA_LLM_STRUCTURED_OUTPUT_MODE` | `NATIVE` | Structured output mode: `NATIVE`, `STRICT`, `LENIENT`, `PROMPT_DIRECTIVE`. `PROMPT_DIRECTIVE` forces JSON-only instructions (use when provider rejects native JSON mode with tools) |
| `AMSHA_LLM_VAULT_PRIORITY` | `false` | When true, committed onboarding LLM settings in the vault take precedence over environment provider settings |

| `AMSHA_LLM_CIRCUIT_BREAKER_ENABLED` | `true` | Enable per-model circuit breaker isolation |

Claude Code uses `CLI_OAUTH` with optional direct API key fallback. The
runtime resolves its credentials from the system vault, macOS Keychain, supported local
Claude Code credential files (`~/.claude/.credentials.json` or
`~/.claude/credentials.json`), or `AMSHA_CLAUDE_CODE_API_KEY` / `CLAUDE_CODE_OAUTH_TOKEN`;
onboarding status reports only whether the credential is configured.

### Onboarding and native first-run testing

`AMSHA_UI_SKIP_ONBOARDING=false` keeps the first-run wizard enabled. `AMSHA_LLM_VAULT_PRIORITY=true` allows committed wizard connections to replace environment-backed LLM routing; it is reported by the authenticated `/api/v1/onboarding/status` response as `vaultPriority`.

For local development only, `AMSHA_BOOTSTRAP_LOCAL_RECOVERY=true` enables the loopback-only onboarding recovery button. It reissues the existing owner credential without deleting data; keep it disabled on shared or production hosts.

For native `dist` testing, `AMSHA_DATA_DIR` selects the runtime database directory. A directory whose bootstrap state is already `CLAIMED` cannot be used for a new anonymous first-owner claim. Choose a new data directory for a fresh test rather than deleting existing data implicitly. After Java or UI changes, rebuild and repackage the native binary; `dist/.env` supplies runtime values when the packaged runner is launched from `dist`.

## Onboarding configuration sources

The web onboarding wizard never edits `.env`. It stores drafts and staged secrets in
the configured persistence/vault layers; `AMSHA_LLM_VAULT_PRIORITY` controls whether
committed system-vault LLM settings override environment configuration. The CLI
first-run wizard is environment bootstrap and may patch the distribution `.env`.
See [ADR: Onboarding Configuration Sources and Precedence](./adr/domain/ADR-Onboarding-Configuration-Source-and-Precedence.md)
for the complete lifecycle and credential-scope rules.

Authenticated onboarding status and settings also report non-secret effective LLM provenance
(`ENVIRONMENT`, `VAULT`, or `DEFAULT`), credential state (`NOT_REQUIRED`, `CONFIGURED`,
or `MISSING`), credential source, and effective base URL. `credentialsOk` remains a workflow
completion flag; use the provenance fields to determine which configuration is active.

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

## Cloudflare Workers AI Provider

Cloudflare Workers AI provider for serverless model inference. Requires an API token and Cloudflare Account ID.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_CLOUDFLARE_API_KEY` | — | Cloudflare API token (Workers AI permissions) |
| `AMSHA_CLOUDFLARE_ACCOUNT_ID` | — | Cloudflare Account ID (resolves `{account_id}` placeholder) |
| `AMSHA_CLOUDFLARE_MODEL` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | Default chat model |
| `AMSHA_CLOUDFLARE_BASE_URL` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` | Base URL for Workers AI endpoint |
| `AMSHA_COMPACTION_CLOUDFLARE_ACCOUNT_ID` | — | Account ID override for compaction slot |
| `AMSHA_CLASSIFIER_CLOUDFLARE_ACCOUNT_ID` | — | Account ID override for classifier slot |
| `AMSHA_COHERE_API_KEY` | — | Cohere API key |
| `AMSHA_COHERE_MODEL` | `command-r-08-2024` | Default chat model |
| `AMSHA_COHERE_BASE_URL` | `https://api.cohere.ai/compatibility/v1` | Base URL for Cohere OpenAI-compatibility endpoint |
| `AMSHA_CODEX_API_KEY` | — | Optional direct API key / token (defaults to `~/.codex/auth.json` OAuth session) |
| `AMSHA_CODEX_MODEL` | `gpt-5.6-luna` | Default chat model |
| `AMSHA_CODEX_BASE_URL` | `https://chatgpt.com/backend-api/codex` | Base URL for Codex endpoint (defaults to a direct ChatGPT Plus Responses endpoint; or `http://127.0.0.1:8787/v1` for Headroom proxy, or `https://api.openai.com/v1`) |

Do not put `{account_id}` inside a MicroProfile `${ENV:default}` default string.
MP Config closes on the first `}`, which corrupts the URL (e.g. trailing `/ai/v1}`).
`application.yaml` keeps the template in `amsha.llm.cloudflare.unresolved-base-url`
and references that property from the env expression instead.

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
Available providers: `groq`, `openai`, `openrouter`, `cerebras`, `gemini`, `nvidia`, `sarvam`,
`opencode`, `huggingface`, `mistral`, `cloudflare`, `cohere`, `codex`, `omlx`, `ollama`, `generic-openai`. Local providers (`ollama`, `omlx`, `codex`) do not require an API key.

Generic OpenAI compaction variables:
`AMSHA_COMPACTION_GENERIC_OPENAI_API_KEY`, `AMSHA_COMPACTION_GENERIC_OPENAI_MODEL`,
`AMSHA_COMPACTION_GENERIC_OPENAI_BASE_URL`

## Intent Classification

Intent classification routes incoming messages to personas or fast-path reflexes.
It uses the same multi-slot failover as compaction, falling back to heuristic routing
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

## Decision Plane & Classifier Runtime Control

The Jev decision plane is a typed judgment seam (`DecisionEngine`). It is **Deferred**: no production
`DecisionEngine` bean is registered, so enabled shadow classifiers return `NoOpinion(MISSING_MODEL)` and
disabled classifiers return `DISABLED`. The shadow switches below request *observational* evaluation — they never change
routing, tool authorization, HITL decisions, or retry budgets. See
[ADR-jev-decision-plane](./adr/architecture/ADR-jev-decision-plane.md).

> **Proposed, not implemented.** An explicit, opt-in evaluation path is specified in
> [ADR-jev-decision-plane-evaluation-path](./adr/architecture/ADR-jev-decision-plane-evaluation-path.md)
> (Status: Proposed) and the
> [activation plan](./research/Jev-Decision-Plane-Activation-Plan-2026-10-07.md). It would allow a real
> credentialed provider call while still registering no `DecisionEngine` bean. **No variable, class, or
> endpoint from it exists yet**, and nothing below changes until that ADR is accepted.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_DECISION_SHADOW_INTENT_ENABLED` | `false` | Enable observational shadow intent classification through `DecisionEngine` |
| `AMSHA_DECISION_SHADOW_TOOL_RISK_ENABLED` | `false` | Enable observational shadow tool-risk evaluation through `DecisionEngine` |
| `AMSHA_DECISION_SHADOW_ANSWER_QUALITY_ENABLED` | `false` | Enable observational shadow answer-quality evaluation through `DecisionEngine` |
| `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED` | `false` | Enable the low-risk typed intent decision pilot router |
| `AMSHA_ROUTING_INTENT_DECISION_ROUTER_REVIEW_BAND_LOW` | `0.50` | Lower review-band threshold; below it a judgment falls back to the deterministic default |
| `AMSHA_ROUTING_INTENT_DECISION_ROUTER_REVIEW_BAND_HIGH` | `0.85` | Upper review-band threshold; at or above it direct routing may apply |
| `AMSHA_ROUTING_INTENT_DECISION_ROUTER_TIMEOUT_MS` | `500` | Per-evaluation deadline for the intent decision router |
| `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ACTIVATION_GATE_CANDIDATE_EVIDENCE_APPROVED` | `false` | Activation gate: candidate calibration on live traffic formally approved |
| `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ACTIVATION_GATE_COST_CEILING_APPROVED` | `false` | Activation gate: cost ceiling and latency budget formally approved |
| `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ACTIVATION_GATE_ROLLOUT_OWNER` | *(unset)* | Activation gate: accountable rollout **and rollback** owner (a person or team, not a status). **No closed set of values** — the only rule enforced is non-blank (`IntentDecisionRouter.isActivationGateApproved()`), so any placeholder opens the gate; leave unset when no owner is assigned. When both approval booleans are true and this value looks like a placeholder, `ActivationGateReadiness` logs one advisory `WARN` at startup — it never rejects or normalises the value |

Two behaviours are worth calling out because both are fail-closed:

- **Enabling a switch without a resolvable `DecisionEngine` activates no decision behavior.** The plane stays
  inert and `DecisionPlaneReadiness` logs a single `WARN` at startup naming the enabled switches and the
  fallback posture. Classifier runtime status also exposes requested and effective state; the warning is the
  dedicated startup diagnostic for this configuration, so a deployment that enables these switches should
  treat its absence as a defect.
- **The intent decision router does not take effect while the activation gate is unapproved.**
  `isEnabled()` requires `enabled=true` *and* an available engine, but direct routing is permitted
  only when `isActivationGateApproved()` also holds (candidate evidence approved, cost ceiling
  approved, and a non-blank rollout owner). With an available engine and the gate unapproved, `evaluateIntent()` returns an
  `ACTIVATION_GATE_NOT_SATISFIED` fallback for non-blank messages and any `DIRECT_ROUTE` is suppressed — so setting
  `AMSHA_ROUTING_INTENT_DECISION_ROUTER_ENABLED=true` on its own is intentionally a no-op.

### Decision provider selection (Jev / System One)

Selects which provider answers decision requests, mirroring the LLM provider convention (`AMSHA_LLM_PROVIDER`
plus `amsha.llm.<provider>.model`). **Inert:** this is the configuration surface only — nothing consumes it into
a `DecisionEngine`, so setting these changes no behaviour, and selecting a provider is not activating the plane.
The [activation plan](./research/Jev-Decision-Plane-Activation-Plan-2026-10-07.md) specifies the wiring that
would consume it, and the governance keys that wiring would require; none of that is implemented.

Two axes: a provider id names the System One **endpoint** that receives `POST /v1/systemone` (as an LLM provider
id names the endpoint receiving `/v1/chat/completions`), and `AMSHA_DECISION_{PROVIDER}_MODEL` names the
checkpoint served there. An id may be a specific decision server (`laya`, `kev`, `clm`), a generic local runtime
host (`omlx`), or a hosted API (`typesafe`).

| Variable | Default | Description |
|---|---|---|
| `AMSHA_DECISION_PROVIDER` | *(unset)* | Provider id to use; must match a declared slot. Blank keeps the plane inert |
| `AMSHA_DECISION_{PROVIDER}_ENABLED` | `false` | Whether the slot may be selected |
| `AMSHA_DECISION_{PROVIDER}_ENDPOINT` | *(per slot)* | The `POST /v1/systemone` endpoint; the lane is derived from its host |
| `AMSHA_DECISION_{PROVIDER}_MODEL` | *(per slot)* | Decision model to request — **required for a remote provider, optional for a self-hosted one** (blank = the server picks its own checkpoint and the field is omitted) |
| `AMSHA_DECISION_{PROVIDER}_API_KEY_REF` | *(unset)* | A `SecretVault` reference, never a literal key. Carried and checked for presence only — no wiring resolves it onto either engine's credentials yet |
| `AMSHA_DECISION_{PROVIDER}_TIMEOUT` | `PT5S` | Request execution timeout |
| `AMSHA_DECISION_{PROVIDER}_WIRE_PROFILE` | `JSON_OBJECT` | How `state` is rendered; belongs to the model — CLM requires `PROSE` |

Declared providers: `typesafe`, `clm`, `laya`, `kev`, `omlx`.

The lane is derived from the endpoint host, not declared: the literal `127.0.0.1` selects the self-hosted lane,
anything else the remote lane. `localhost`, `::1`, and other `127.x` hosts are refused as ambiguous. A **remote**
slot with no `model` is refused (a self-hosted one may omit it), as is a credentialed remote provider
(`typesafe`) with no `api-key-ref`, a non-positive `timeout`, and a CLM model declared without
`WIRE_PROFILE=PROSE`.

Classifier *runtime* control (`/api/v1/admin/local-ml/onnx/classifiers*`) is a separate admin
surface with its own requested-versus-effective semantics; see
[In-Process Local ML (ONNX Runtime)](#in-process-local-ml-onnx-runtime) and
[Architecture Invariants](./Architecture_Invariants.md) §7.13.4. The cockpit mirrors only the
read-only half at `#/classifiers`, which requires the `internal` role and reports no
activation-gate, evidence, cost-approval, or rollout-owner state.

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
| `AMSHA_INFINITE_LOOP_COOLDOWN_BASE_SECONDS` | `5` | Base cooldown between provider switches (s) |
| `AMSHA_INFINITE_LOOP_COOLDOWN_MAX_SECONDS` | `60` | Max cooldown (s) |
| `AMSHA_INFINITE_LOOP_COOLDOWN_TRANSIENT_SECONDS` | `5` | Transient error cooldown (s) |
| `AMSHA_INFINITE_LOOP_MAX_LATENCY_MS` | `45000` | Max acceptable latency (ms) |

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
| `AMSHA_RAG_VEC_AUTO_DOWNLOAD` | `false` | Auto-download the sqlite-vec release asset (SHA-256 verified) when missing from disk and JAR. Opt-in; offline-safe by default |
| `AMSHA_RAG_VEC_VERSION` | `0.1.9` | sqlite-vec version used by the auto-downloader; 0.1.9 digests are pinned in code, other versions are verified against the release checksums.txt |
| `AMSHA_RAG_RERANKER_ENABLED` | `false` | Enable local ONNX cross-encoder reranking (`RerankerPort` via `LocalOnnxScoringAdapter`). Fail-closed: disabled by default |
| `AMSHA_RAG_RERANKER_BUNDLE_ID` | `bge-reranker-v1` | Verified local cross-encoder bundle ID under `AMSHA_ONNX_MODEL_ROOT` |
| `AMSHA_RAG_RERANKER_TIMEOUT` | `PT5S` | Total deadline for one cross-encoder scoring request (ISO-8601 duration) |
| `AMSHA_RAG_RERANKER_MAX_CANDIDATES` | `50` | Maximum retrieved candidate chunks scored per query (excess retains RRF order) |
| `AMSHA_RAG_RERANKER_MAX_INPUT_BYTES` | `262144` | UTF-8 byte limit (256 KB) for query or candidate text before scoring (excess rejected) |

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
| `AMSHA_RAG_RERANKER_ENABLED` | `false` | Duplicate alias for RAG reranker enablement (see Memory & RAG); kept here for discoverability |
| `AMSHA_RAG_RERANKER_BUNDLE_ID` | `bge-reranker-v1` | Duplicate alias for reranker bundle ID |
| `AMSHA_RAG_RERANKER_TIMEOUT` | `PT5S` | Duplicate alias for reranker deadline |
| `AMSHA_RAG_RERANKER_MAX_CANDIDATES` | `50` | Duplicate alias for reranker candidate window |
| `AMSHA_RAG_RERANKER_MAX_INPUT_BYTES` | `262144` | Duplicate alias for reranker byte limit |

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
| `AMSHA_TELEGRAM_BASE_URI` | `https://api.telegram.org` | Telegram Bot API base URI for getFile and file downloads |
| `AMSHA_CHANNEL_TELEGRAM_MAX_LENGTH` | `4096` | Max Telegram message length |

## MCP

| Variable | Default | Description |
|---|---|---|
| `AMSHA_MCP_SERVER_ENABLED` | `true` | Enable outbound MCP client management REST endpoints. |
| `AMSHA_MCP_SETUP_TOKEN_SECRET` | empty | Stable HMAC-SHA-256 secret for ten-minute, single-use, user-bound setup/edit links. |
| `AMSHA_MCP_SETUP_UI_BASE_URL` | empty | Public UI base URL for absolute setup/edit links issued by remote channels. |
| `AMSHA_MCP_HEALTH_CHECK_INTERVAL_MS` | `15000` | Health-check interval for active HTTP/SSE MCP connections. |
| `AMSHA_MCP_PODMAN_HEALTH_CHECK_INTERVAL_MS` | `15000` | Health-check interval for Podman MCP connections. |
| `AMSHA_WEB_REQUIRE_AUTH` | `false` | Require WebSocket authentication; the browser platform-ID continuity fallback is accepted only when false. |

MCP management behavior is documented in [MCP Management](./MCP_MANAGEMENT.md).

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

The application CORS policy permits `GET`, `POST`, `PUT`, `PATCH`, `DELETE`,
and `OPTIONS`. Keep `PUT` enabled because onboarding draft step transitions and
several administrative resources use it; browser preflight otherwise rejects
the request before authentication or resource handling.

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

Application logs are written to a profile/backend-specific file in the process
working directory. SQLite filenames follow `amsha-<profile>-sqlite.log`
(for example, `amsha-prod-sqlite.log` and `amsha-dev-sqlite.log`). The
PostgreSQL and filesystem profiles use `amsha-postgresql-postgres.log` and
`amsha-filesystem-fs.log`. Set `AMSHA_LOG_CONSOLE_ENABLED=true` to also print
application logs in the terminal; it defaults to `false`.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_OBSERVABILITY_CHAT_LISTENER_MODE` | `off` | LangChain4j `ChatModelListener` pilot mode (`off` = no-op, `shadow` = shadow metrics/timers with zero payload, `active` = `amsha.llm.listener_call` spans + active metrics for calls not already manually instrumented). `ChatModelListenerRuntimeControl` fail-closed: enables to `shadow`/`active` only when a ready `AmshaChatModelListener` CDI implementation is registered; disabling drains in-flight leases. |
| `AMSHA_PHOENIX_ENABLED` | `true` | Enable OTel trace export to Arize Phoenix |
| `AMSHA_PHOENIX_ENDPOINT` | `http://localhost:4318/v1/traces` | Phoenix OTLP HTTP endpoint |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry collector endpoint |
| `OTEL_SDK_DISABLED` | `false` | Disable OpenTelemetry SDK |

## Media Ingestion & Download Limits

Governed, streaming-validated media downloads for multimodal attachments (`MediaDownloadPort` / `GovernedMediaDownloadService` in `amsha-security`). All downloads enforce size, MIME, and SSRF guards before buffering; payloads land in deterministic temp files (`DownloadedMedia`) with `AutoCloseable` cleanup.

| Variable | Default | Description |
|---|---|---|
| `AMSHA_MEDIA_MAX_IMAGE_SIZE_MB` | `10` | Maximum size in megabytes for a single image attachment (`image/png`, `image/jpeg`, `image/webp`, or `image/gif`). Streaming fail-closed when exceeded. |
| `AMSHA_MEDIA_MAX_DOCUMENT_SIZE_MB` | `25` | Maximum size in megabytes for a single document/audio attachment (`application/pdf`, `audio/mpeg`, `audio/wav`, `audio/x-wav`, or `audio/ogg`). `audio/x-wav` is canonicalized to `audio/wav`. |
| `AMSHA_MEDIA_MAX_TOTAL_PAYLOAD_MB` | `50` | Maximum total megabytes summed across all attachments in a single inbound turn. Multi-attachment batch fail-closed when exceeded. |
| `AMSHA_MEDIA_CONNECT_TIMEOUT_SECONDS` | `10` | Governed HTTP connect timeout in seconds (`GovernedHttpClient` via `OutboundHttpPolicyService`). |
| `AMSHA_MEDIA_READ_TIMEOUT_SECONDS` | `30` | Governed HTTP read timeout in seconds; streaming read loop enforces size incrementally, not after buffering. |

Constants and port live in `amsha-security-api` (`MediaBudgetLimits`, `MediaDownloadPort`, `DownloadedMedia`); implementation and `MediaSecurityValidator` (SSRF/DNS, redirect revalidation, IP blocklist) live in `amsha-security`. Channel media types: `ChannelMessage` → `MediaAttachment` → `DownloadedMedia` → tool context (`OutboundPurpose.MEDIA_DOWNLOAD`). Telegram emits opaque `telegram://file/<file_id>` references resolved through `GovernedTelegramMediaResolver`. See [SECURITY.md](./SECURITY.md#governed-media-download) and [CHANNEL_SPI.md](./CHANNEL_SPI.md#multimodal-media).

## Speech Synthesis (TTS) & Recognition (STT)

| Variable | Default | Description |
|---|---|---|
| `AMSHA_TTS_ENABLED` | `false` | Enables the optional OMLX Qwen3-TTS output path. |
| `AMSHA_TTS_MODEL` | `Qwen3-TTS-12Hz-1.7B-Base-bf16` | OMLX model identifier for TTS synthesis. |
| `AMSHA_TTS_VOICE` | *(empty)* | Optional voice identifier; omitted from the provider request when blank. |
| `AMSHA_TTS_RESPONSE_FORMAT` | `wav` | TTS response format. V1 supports WAV only; other values fail closed as `UNSUPPORTED`. |
| `AMSHA_TTS_TIMEOUT` | `PT120S` | Maximum TTS provider request duration. |
| `AMSHA_TTS_MAX_INPUT_CHARS` | `1200` | Maximum projected prose sent to synthesis. |
| `AMSHA_TTS_MAX_BYTES` | `8388608` | Maximum streamed TTS response size (8 MiB). |
| `AMSHA_TTS_MAX_CONCURRENT` | `1` | Maximum concurrent TTS requests. |
| `AMSHA_TTS_DEFAULT_OUTPUT_MODE` | `TEXT_ONLY` | Default mode for inbound-audio turns without an explicit directive (`TEXT_ONLY`, `AUDIO_ONLY`, `TEXT_AND_AUDIO`). |
| `AMSHA_STT_ENABLED` | `false` | Enables the governed OMLX Whisper transcription path. |
| `AMSHA_STT_MODEL` | `whisper-large-v3-turbo` | OMLX Whisper model identifier for remote STT. |
| `AMSHA_STT_TIMEOUT` | `PT60S` | Maximum remote STT provider request duration. |
| `AMSHA_STT_MAX_BYTES` | `26214400` | Maximum remote STT audio payload size (25 MiB). |
| `AMSHA_STT_MAX_TRANSCRIPT_CHARS` | `12000` | Maximum remote STT transcript length. |
| `AMSHA_STT_LOCAL_ENABLED` | `false` | Enables in-process ONNX Whisper under `AudioTranscriptionPort`; requires `AMSHA_ONNX_ENABLED=true`. |
| `AMSHA_STT_LOCAL_BUNDLE_ID` | `whisper-tiny-en-v1` | Verified local Whisper bundle identifier under `data/models/`. |
| `AMSHA_STT_LOCAL_TIMEOUT` | `PT30S` | Total deadline for one local audio transcription request. |
| `AMSHA_STT_LOCAL_MAX_AUDIO_BYTES` | `26214400` | Maximum local STT audio payload size (25 MiB). |
| `AMSHA_STT_LOCAL_MAX_TRANSCRIPT_CHARS` | `12000` | Maximum local STT transcript length. |
| `AMSHA_STT_FFMPEG_PATH` | `ffmpeg` | Executable used to transcode compressed audio to mono 16 kHz WAV. |
| `AMSHA_STT_ALLOW_FALLBACK_ON_FAILURE` | `false` | Allows the composite STT port to try a lower-priority provider after a failure or timeout. Keep false to prevent unintended audio egress. |

Both STT and TTS reuse `AMSHA_OMLX_API_KEY` and `AMSHA_OMLX_BASE_URL` for the OMLX provider. STT is an execution port, not a chat-model capability: local ONNX Whisper has priority over OMLX, while `UNSUPPORTED` results may cascade and failures/timeouts cascade only when explicitly enabled. TTS is output-only, governed, and currently emits WAV only; it does not create an `AUDIO` model route.

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
