# ADR-Kimchi-Provider: Kimchi LLM Provider Integration

- **Status**: Approved
- **Date**: 2026-08-19
- **Provider ID**: `kimchi`
- **Default Model**: `minimax-m3`
- **Default Base URL**: `https://llm.kimchi.dev/openai/v1`
- **Credential Type**: `CLI_OAUTH` / Direct API Key

---

## 1. Context

Kimchi (`https://kimchi.dev`) provides high-throughput inference for open-source and frontier models including MiniMax M3, DeepSeek V4 Flash, Kimi K2.7, and Nemotron Ultra.

Developers using the Kimchi CLI or OpenCode toolchain authenticate via browser OAuth which writes credential tokens to `~/.config/kimchi/config.json` or `~/.config/opencode/opencode.json`, macOS Keychain, or environment variables (`AMSHA_KIMCHI_API_KEY`, `KIMCHI_API_KEY`).

---

## 2. Decision

1. **Provider Adapter**:
   - `KimchiResponsesChatModel` provides direct HTTP SSE streaming over `https://llm.kimchi.dev/openai/v1/chat/completions`.
   - Sends `User-Agent: kimchi/0.1.92` to match CLI client signature requirements.
   - Enforces zero-logging of raw API tokens; secret tokens are stored as `char[]` and zeroed on close.

2. **Credential Extraction & Token Resolution**:
   - `KimchiOAuthTokenExtractor` checks candidate configuration files:
     - `~/.config/kimchi/config.json`
     - `~/.config/opencode/opencode.json`
     - Environment variables: `AMSHA_KIMCHI_API_KEY`, `KIMCHI_API_KEY`, `OPENCODE_API_KEY`
   - `@ApplicationScoped KimchiTokenResolver` handles resolution, caching, and fallback hierarchies.

3. **Routing and Reasoning SPIs**:
   - `KimchiProviderFactory` implements `AgentChatModelProvider`.
   - `KimchiLlmProviderFactory` implements `RoutingChatModelProvider`.
   - Registered in `provider-catalog.json` for onboarding UI discovery.

4. **Slots Supported**:
   - Primary LLM: `AMSHA_LLM_PROVIDER=kimchi` / `AMSHA_KIMCHI_MODEL`
   - Intent Classifier: `amsha.classifier.kimchi.*` / `AMSHA_CLASSIFIER_KIMCHI_MODEL`
   - Context Compaction: `amsha.compaction.kimchi.*` / `AMSHA_COMPACTION_KIMCHI_MODEL`
   - Multi-provider fallback slot in `infinite-loop`.

---

## 3. Consequences

- Full support for Kimchi cloud inference across agent reasoning, routing, compaction, and classification.
- Seamless developer onboarding via existing CLI session tokens with zero manual copy-pasting of API keys.
