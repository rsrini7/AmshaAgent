# ADR-Claude-Code-Provider: Claude Code CLI / Anthropic Provider Integration

## Status
Accepted

## Context
Anthropic's Claude Code CLI (`claude`) allows developers to utilize frontier Claude models (`claude-sonnet-4-5`, `claude-3-7-sonnet-20250219`, `claude-3-5-sonnet-20241022`).
Claude Code manages OAuth tokens across macOS Keychain entries (`Claude Code-credentials`), Linux/Windows credential files (`~/.claude/.credentials.json`), and environment variables (`CLAUDE_CODE_OAUTH_TOKEN`, `AMSHA_CLAUDE_CODE_API_KEY`).
Previously, `claude-code` passed tokens into LangChain4j's `AnthropicChatModel` via `apiKey(...)`, which sends `x-api-key: ...`. However, Anthropic's Messages API requires `Authorization: Bearer <token>` and `anthropic-beta: oauth-2024-11-01` for OAuth tokens, causing authentication failures for CLI OAuth subscribers.

## Decision
1. **Provider Identification**:
   - Provider ID: `claude-code`
   - Default Model: `claude-sonnet-4-5`
   - Default Base URL: `https://api.anthropic.com`
   - Credential Type: `CLI_OAUTH`
   - Vault Key: `VaultConstants.CLAUDE_CODE_OAUTH_TOKEN_KEY` (`claude-code-oauth-token`)
2. **Direct Anthropic Messages Transport**:
   - `ClaudeCodeResponsesChatModel` connects directly to Anthropic Messages endpoint (`https://api.anthropic.com/v1/messages` or custom base URL) using SSE streaming.
   - Automatically detects authentication type:
     - For standard API keys (`sk-ant-api...`), sets `x-api-key: <key>`.
     - For OAuth tokens, sets `Authorization: Bearer <token>` and `anthropic-beta: oauth-2024-11-01`.
   - Supports tool execution, tool response messages, system message extraction, and multi-turn message normalization.
   - Full `TokenUsage` extraction from `message_start` and `message_delta` events prevents 0-token degraded response loops.
3. **Credential Extraction & Token Resolution**:
   - `ClaudeCodeOAuthTokenExtractor` checks macOS Keychain (`Claude Code-credentials`, `claude-code-credentials`, `Claude Code`), candidate file paths (`~/.claude/.credentials.json`, `~/.claude/credentials.json`, `~/.claude.json`, `$CLAUDE_CONFIG_DIR`), and environment variables.
   - Shared `@ApplicationScoped ClaudeCodeTokenResolver` handles token resolution, secret vault caching, and fallback hierarchies.
   - Provides clear actionable instructions if login is missing: `Please run 'claude login' in your local terminal first, or set AMSHA_CLAUDE_CODE_API_KEY / CLAUDE_CODE_OAUTH_TOKEN.`
4. **Reflection & Config**:
   - Registered in `CoreReflectionConfig` (`ClaudeCodeOAuthTokenExtractor.class`, `ClaudeCodeTokenResolver.class`, `ClaudeCodeResponsesChatModel.class`).
   - Configuration properties mapped in `application.yaml` and `.env.example`.

## Consequences
- Claude Code CLI / Anthropic is available as a first-class provider (`AMSHA_LLM_PROVIDER=claude-code`) and as a slot in `infinite-loop`.
- Both standard Anthropic API keys and Claude Code CLI OAuth subscriber sessions work seamlessly with zero external proxies.
