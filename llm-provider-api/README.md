# amsha-llm-provider-api

Pure API contracts, Service Provider Interfaces (SPIs), Data Transfer Objects (DTOs), OAuth extractors, and configuration constants for LLM provider integration in Amsha Agent.

`amsha-llm-provider-api` contains **zero dependencies on `amsha-core`**, establishing a clean architectural boundary that allows downstream consumers (`amsha-onboarding`, `amsha-rag`, `amsha-persistence-*`, `amsha-local-ml-onnx`) to reference LLM models and contracts without pulling in heavy core runtime machinery.

## Core Interfaces and SPIs

| Interface / Class | Package | Purpose |
|---|---|---|
| `AgentChatModelProvider` | `com.amsha.llm.api` | SPI implemented by reasoning model factories. Provides `ChatModel` instances configured for agent execution loops. |
| `RoutingChatModelProvider` | `com.amsha.llm.api` | SPI implemented by routing model factories. Provides `ChatModel` instances for lightweight intent classification, routing, and compaction. |
| `ChatModelCreationPort` | `com.amsha.llm.api` | Decoupling port implemented by `amsha-llm-providers` (`ChatModelFactory`) and consumed by `amsha-core` (`ChatModelProducer`, `ModelRouter`). |
| `LlmProviderOnboardingAdapter` | `com.amsha.llm.api` | Port interface for provider catalog introspection, model discovery, and onboarding validation. |
| `ModelDefaults` | `com.amsha.llm.api` | Central registry of default provider IDs, model identifiers, base URLs, and timeout constants. |
| `AgentLlmConfig` / `LlmModelConfig` | `com.amsha.llm.api` | Immutable configuration records for per-provider and per-agent LLM parameters. |
| `StrictJsonSchemaCapable` | `com.amsha.llm.api` | Capability interface implemented by provider models and routing wrappers that advertise verified support for strict JSON Schema response formatting. |
| `StructuredSchemaRequests` | `com.amsha.llm.api` | Utilities for request-scoped schema attachment, schema conversion to LangChain4j ResponseFormat, schema removal, and candidate rewrite dispatch. |
| `StructuredOutputMode` | `com.amsha.llm.api` | Enum defining structured JSON output handling (`NATIVE`, `PROMPT_DIRECTIVE`, `LENIENT`). |
| `NetworkProfilePolicy` | `com.amsha.llm.api` | Outbound network governance, proxy settings, and TLS trust mode policies for LLM traffic. |

## OAuth Token Extractors

Located in `com.amsha.llm.api.oauth`:
- `AgyOAuthTokenExtractor`: Reads credentials for Antigravity / Gemini CLI (`~/.gemini/antigravity-cli/auth.json`).
- `ClaudeCodeOAuthTokenExtractor`: Reads credentials for Claude Code CLI (`~/.claude/.credentials.json`, `~/.claude.json`, macOS Keychain).
- `CodexOAuthTokenExtractor`: Reads credentials for OpenAI Codex / ChatGPT Plus CLI (`~/.codex/auth.json`).
- `KimchiOAuthTokenExtractor`: Reads credentials for Kimchi CLI (`~/.config/kimchi/config.json`, `~/.config/opencode/opencode.json`, macOS Keychain).

## Downstream Usage

To declare a dependency on provider contracts without coupling to concrete LangChain4j SDKs:

```xml
<dependency>
  <groupId>com.amsha</groupId>
  <artifactId>amsha-llm-provider-api</artifactId>
</dependency>
```
