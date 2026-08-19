# ADR-Antigravity-Provider: Antigravity CLI & Gemini Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `agy`
- **Default Model**: `gemini-3.1-flash-lite` (or `gemini-2.5-pro`)
- **Default Base URL**: `https://generativelanguage.googleapis.com/v1beta/openai`
- **Credential Type**: `CLI_OAUTH` / Direct API Key

---

## 1. Context

Google Antigravity CLI (`agy`) provides agentic capabilities backed by Gemini models. Developers authenticated with `agy login` store session tokens in `~/.gemini/antigravity-cli/auth.json` or macOS Keychain.

---

## 2. Decision

1. **Adapter & Transport**:
   - `AgyResponsesChatModel` connects to Google's OpenAI-compatible endpoint (`https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`).
   - Resolves CLI OAuth bearer tokens or Gemini API keys (`AMSHA_AGY_API_KEY`, `AMSHA_GEMINI_API_KEY`).
   - Uses `AgyCliExecutor` as a fallback CLI executor when direct network is governed or in offline dev environments.

2. **Credential Extraction**:
   - `AgyOAuthTokenExtractor` discovers active session credentials from:
     - `~/.gemini/antigravity-cli/auth.json`
     - macOS Keychain `Antigravity CLI-credentials`
     - Environment variables: `AMSHA_AGY_API_KEY`, `GEMINI_API_KEY`

3. **Factories & Reflection**:
   - `AgyProviderFactory` (`AgentChatModelProvider`) and `AgyLlmProviderFactory` (`RoutingChatModelProvider`).
   - Registered in native reflection configuration and `provider-catalog.json`.

---

## 3. Consequences

- Direct integration with Google Antigravity ecosystem and Gemini reasoning models.
