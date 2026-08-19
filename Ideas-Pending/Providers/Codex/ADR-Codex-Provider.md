# ADR-Codex-Provider: Codex CLI / ChatGPT Plus OAuth Provider Integration

## Status
Accepted

## Context
Amsha Agent supports multiple LLM providers, including zero-configuration CLI OAuth extraction for `claude` via Claude Code (`~/.claude/credentials.json`).
Users with active ChatGPT Plus subscriptions using the Codex CLI (`/opt/homebrew/bin/codex`) have local OAuth credentials stored at `~/.codex/auth.json` and optional local proxy settings (`hroom` / Headroom).
Integrating `codex` as a first-class provider in Amsha allows users to utilize OpenAI's frontier reasoning and chat models (e.g. `gpt-5.6-luna`, `gpt-4o`) under their existing ChatGPT Plus subscription without per-token API charges and without requiring external proxies.

## Decision
1. **Provider Identification**:
   - Provider Name: `codex`
   - Default Model: `gpt-5.6-luna`
   - Default Base URL: `https://chatgpt.com/backend-api/codex` (direct ChatGPT Plus Responses endpoint default)
   - Alternative Base URLs: `http://127.0.0.1:8787/v1` (`hroom` / Headroom proxy) or `https://api.openai.com/v1` (direct OpenAI developer API)
   - Credential Type: `CLI_OAUTH`
   - Vault Key: `VaultConstants.CODEX_OAUTH_TOKEN_KEY` (`codex-oauth-token`)
2. **Direct ChatGPT Plus Responses Transport**:
   - `CodexResponsesChatModel` connects directly to `https://chatgpt.com/backend-api/codex/responses` using SSE streaming, passing `Authorization: Bearer <access_token>` and `ChatGPT-Account-ID: <account_id>` headers with `store: false`.
   - Supports text generation, multi-turn dialogue, tool execution, and tool results natively.
   - When configured with proxy/custom URLs (e.g. `http://127.0.0.1:8787/v1` or `https://api.openai.com/v1`), Amsha routes through `OpenAiChatModel` via `ChatModelFactory.createOpenAi`.
3. **Credential Extraction & Token Resolution**:
   - `CodexOAuthTokenExtractor` reads `~/.codex/auth.json`.
   - Parses `tokens.access_token` and `tokens.account_id`, falling back to top-level `OPENAI_API_KEY`. OIDC `id_token` is excluded.
   - Shared `@ApplicationScoped CodexTokenResolver` manages token resolution, secret vault caching, and env/system property fallbacks across reasoning and routing factories.
   - Provides clear actionable instructions if login is missing: `Please run 'codex login' in your local terminal first, or set AMSHA_CODEX_API_KEY.`
4. **SPI Conformance & Shared Transport**:
   - `CodexProviderFactory` (`AgentChatModelProvider` in `com.amsha.llm.providers.reasoning`) uses `ChatModelFactory.buildMultiModelEntries` and `ChatModelFactory.createCodex`.
   - `CodexLlmProviderFactory` (`RoutingChatModelProvider` in `com.amsha.llm.providers.routing`) delegates to `ChatModelFactory.createCodex` for direct ChatGPT endpoints and `ChatModelFactory.openAiBuilder` for proxy endpoints.
5. **Reflection & Config**:
   - Registered in `LlmProvidersReflectionConfig` and `LlmProviderApiReflectionConfig` (`CodexOAuthTokenExtractor.class`, `CodexTokenResolver.class`, `CodexResponsesChatModel.class`).

## Consequences
- Codex CLI / ChatGPT Plus is available as a first-class provider (`AMSHA_LLM_PROVIDER=codex`) and as a slot in `infinite-loop`.
- Zero external proxy daemons are required for ChatGPT Plus subscribers.
