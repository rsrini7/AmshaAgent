# amsha-llm-providers

Houses all 21 built-in concrete LLM provider factories, LangChain4j concrete SDKs (`quarkus-langchain4j-openai`, `quarkus-langchain4j-anthropic`, `quarkus-langchain4j-ollama`, `quarkus-langchain4j-ai-gemini`), custom HTTP/SSE response wrappers, token resolvers, and `ChatModelFactory`.

`amsha-llm-providers` implements the contracts defined in `amsha-llm-provider-api`. `amsha-core` consumes providers decoupled via `ChatModelCreationPort` and CDI `Instance<T>` multi-bean discovery.

## Supported Providers

| Provider | Provider ID | Default Model | Default Base URL |
|---|---|---|---|
| Groq | `groq` | `meta-llama/llama-4-scout-17b-16e-instruct` | `https://api.groq.com/openai/v1` |
| OpenAI | `openai` | `gpt-4o-mini` | `https://api.openai.com/v1` |
| Ollama | `ollama` | `mistral-small:latest` | `http://localhost:11434` |
| OpenRouter | `openrouter` | `minimax/minimax-m3:free` | `https://openrouter.ai/api/v1` |
| NVIDIA | `nvidia` | `meta/llama-3.2-11b-vision-instruct` | `https://integrate.api.nvidia.com/v1` |
| Cerebras | `cerebras` | `qwen-3.8-27b` | `https://api.cerebras.ai/v1` |
| Sarvam AI | `sarvam` | `sarvam-105b-conversations` | `https://api.sarvam.ai/v1` |
| OpenCode | `opencode` | `deepseek-v4-flash-free` | `https://opencode.ai/zen/v1` |
| Hugging Face | `huggingface` | `meta-llama/Llama-3.3-70B-Instruct` | `https://router.huggingface.co/v1` |
| Mistral AI | `mistral` | `codestral-latest` | `https://api.mistral.ai/v1` |
| Cloudflare Workers AI | `cloudflare` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` |
| Cohere | `cohere` | `command-r-08-2024` | `https://api.cohere.ai/compatibility/v1` |
| Codex CLI | `codex` | `gpt-5.6-luna` | `https://chatgpt.com/backend-api/codex` |
| Claude Code | `claude-code` | `claude-sonnet-4-5` | `https://api.anthropic.com` |
| Antigravity CLI | `agy` | `gemini-3.1-flash-lite` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| Kimchi | `kimchi` | `minimax-m3` | `https://llm.kimchi.dev/openai/v1` |
| Foundry Local | `foundry` | `qwen3-14b` | `http://127.0.0.1:8000/v1` (or dynamic daemon port) |
| Gemini | `gemini` | `gemini-3.1-flash-lite` | `https://generativelanguage.googleapis.com/v1beta` |
| OMLX | `omlx` | `mlx-community/Qwen3.6-35B-A3B-4bit` | `http://127.0.0.1:8000/v1` |
| Llama.cpp | `llamacpp` | `qwen3` | `http://127.0.0.1:9000/v1` |
| Generic OpenAI | `generic-openai` | `gpt-4o-mini` | *(Configured via `AMSHA_GENERIC_OPENAI_BASE_URL`)* |

## Architecture & CDI Discovery

Concrete provider factories are annotated with `@ApplicationScoped` and discovered dynamically at runtime:
- **Agent Reasoning**: Implements `AgentChatModelProvider` in package `com.amsha.llm.providers.reasoning`.
- **Routing & Subsystems**: Implements `RoutingChatModelProvider` in package `com.amsha.llm.providers.routing`.
- **Custom Responses Chat Models**: Custom streaming & thinking-tag wrappers in `com.amsha.llm.providers.wrapper`.
- **Token Resolvers & CLI Helpers**: Caching and fallback resolution in `com.amsha.llm.providers.helper`.
- **Native Reflection**: Registered in `LlmProvidersReflectionConfig`.

## Governed HTTP Transport (`GovernedLangChainHttpClient`)

Outbound provider requests across OpenAI, Anthropic, Gemini, Ollama, LlamaCpp, and custom OpenAI-compatible providers are routed through Amsha's governed transport layer:
- **`GovernedLangChainHttpClient`**: Custom LangChain4j `HttpClient` implementation that delegates requests to `GovernedTransportService.send()` with purpose `OutboundPurpose.LLM_INFERENCE`.
- **`GovernedLangChainHttpClientBuilder`** and **`GovernedLangChainHttpClientBuilderFactory`**: Registered via Java SPI (`META-INF/services/dev.langchain4j.http.client.HttpClientBuilderFactory`) and configured as the default factory system property (`dev.langchain4j.http.client.builder.factory`).
- **Security Invariants Enforced**:
  - **SSRF Defense**: IP blocklist filtering prevents routing to loopback, private IPv4/IPv6, or cloud metadata IP ranges (`169.254.169.254`).
  - **Credential Leak Prevention**: Inspects request payloads before dispatch to ensure raw vault pepper, API secret keys, or passwords are not inadvertently echoed.
  - **Actor Propagation**: Extracts authenticated user ID from `ScopedValue` (`USER_ID`) or session context and binds it to the audit record.
  - **Audit Logging**: Successful outbound dispatches record `EGRESS_TRANSPORT_SUCCESS` in the tamper-evident HMAC audit log. Blocked attempts record `EGRESS_TRANSPORT_BLOCKED`.
  - **Metrics**: Emits `amsha.transport.requests.total` and `amsha.transport.duration` Micrometer metrics.

## Multimodal Model Capabilities

Provider models declare or dynamically resolve multimodal capabilities via `MultimodalCapability` (contract from `amsha-llm-provider-api`):
- **OpenAI Vision**: `gpt-4o`, `gpt-4-turbo`, `o1` support `IMAGE` (PNG/JPEG/WEBP/GIF) up to 20 MiB, up to 10 images per request, inline base64 content.
- **Gemini**: `gemini-1.5-pro`, `gemini-2.0-flash`, etc. support `IMAGE`, `AUDIO`, and `DOCUMENT_PDF` up to 25 MiB, up to 16 media items per request.
- **Anthropic**: `claude-3-5-sonnet`, `claude-3-haiku` support `IMAGE` and `DOCUMENT_PDF` up to 25 MiB, up to 10 media items per request.
- **Open-Weight Models**: `llava`, `pixtral`, etc. support `IMAGE` up to 10 MiB, up to 5 images per request.
- **OMLX**: Recognized local Qwen vision families are `IMAGE`-only in this contract; audio is not inferred from an arbitrary model name. To transcribe Telegram audio, select an audio-capable Gemini model with `AMSHA_LLM_PROVIDER=gemini` and `AMSHA_GEMINI_API_KEY`, or configure only `gemini` in `AMSHA_INFINITE_LOOP_LLM_PROVIDERS`. Do not combine OMLX with Gemini for this turn because the safe composite intersection removes `AUDIO`.
- **Text-Only Models & Failover**: Models without vision/audio capabilities default to `MultimodalCapability.TEXT_ONLY`. When a turn contains media attachments, `MultimodalMessageMapper` in `amsha-core` automatically provides structured fallback text placeholders so the turn completes safely.

Provider URL capability declarations do not authorize the mapper to forward source URLs. URI-backed attachments are fetched only through the governed media port and are forwarded as validated inline bytes; URL-only transport currently degrades to a text placeholder.

## Observability & ChatModelListener Pilot

For this pilot, OpenAI-compatible `OpenAiChatModel` instances built via `ChatModelFactory.openAiBuilder()` attach `AmshaChatModelListener` if discovered in the CDI container (other provider SDKs remain Amsha-observed). The listener operates under `ChatModelListenerRuntimeControl`:
- `off` (default): Listener rejects request acquisition and performs 0 operations.
- `shadow`: Records shadow metrics (`amsha.observability.listener.shadow.*`) and duration timers for parity comparison against authoritative `AgentObserver` metrics; no sample queue is retained.
- `active`: Emits active `amsha.llm.listener_call` child spans with OTel semantic conventions and active metrics/timers for calls without an existing manual instrumentation marker. Manually instrumented calls remain authoritative and are not duplicated.

| Property | Env Var | Default | Description |
|---|---|---|---|
| `amsha.observability.chat-listener.mode` | `AMSHA_OBSERVABILITY_CHAT_LISTENER_MODE` | `off` | Mode for LangChain4j `ChatModelListener` pilot (`off`, `shadow`, `active`) |

## Adding a New Provider

To add a new provider to `amsha-llm-providers`:
1. Add constant identifiers in `amsha-llm-provider-api` (`ModelDefaults.java`).
2. Implement `AgentChatModelProvider` in `llm-providers/src/main/java/com/amsha/llm/providers/reasoning/<Name>ProviderFactory.java`.
3. Implement `RoutingChatModelProvider` in `llm-providers/src/main/java/com/amsha/llm/providers/routing/<Name>LlmProviderFactory.java`.
4. Register the new factory classes in `LlmProvidersReflectionConfig.java`.
5. Add unit coverage in `AgentProviderFactoryCoverageTest` and `RoutingProviderFactoryCoverageTest`.

## Governed Whisper transcription

`OmlxAudioTranscriptionProvider` implements the `AudioTranscriptionPort` execution seam for
Whisper served by OMLX. It is deliberately not a chat-model capability or a model-route
profile. When enabled, an unsupported audio attachment is downloaded through the governed
media port, transcribed locally, and passed as bounded text to the selected text model.

| Property | Environment variable | Default |
|---|---|---|
| `amsha.stt.enabled` | `AMSHA_STT_ENABLED` | `false` |
| `amsha.stt.model` | `AMSHA_STT_MODEL` | `whisper-large-v3-turbo` |
| `amsha.stt.timeout` | `AMSHA_STT_TIMEOUT` | `PT60S` |
| `amsha.stt.max-bytes` | `AMSHA_STT_MAX_BYTES` | `26214400` |
| `amsha.stt.max-transcript-chars` | `AMSHA_STT_MAX_TRANSCRIPT_CHARS` | `12000` |
| `amsha.stt.ffmpeg-path` | `AMSHA_STT_FFMPEG_PATH` | `ffmpeg` |

The OMLX API key and base URL are reused from `AMSHA_OMLX_API_KEY` and
`AMSHA_OMLX_BASE_URL`. Provision the Whisper processor/tokenizer sidecars before starting
OMLX; see `scripts/provision_omlx_whisper_stt.sh`.

### OGG/Opus → WAV conversion

OMLX Whisper cannot decode OGG/Opus voice notes, so `OmlxAudioTranscriptionProvider` transcodes
them to mono 16 kHz PCM WAV before the transcription call. The conversion runs **after the
governed media download and before the Whisper request** — never inside the Telegram adapter.

* Detection is by MIME type and magic bytes (`OggS` for OGG/Opus, `RIFF…WAVE` for WAV).
* WAV input passes through unchanged.
* OGG/Opus is transcoded with an allowlisted `ffmpeg` invocation built via `ProcessBuilder`
  (no shell); the executable path comes from `amsha.stt.ffmpeg-path`, not the attachment.
  The output is forced to mono 16 kHz PCM WAV via `-c:a pcm_s16le`, and ffmpeg's own `-fs`
  limit plus a post-transcode size check (stat before reading into memory) enforce the bound.
* The 25 MB payload bound and the configured timeout are enforced; temporary files are removed
  in a `finally` block. ffmpeg stderr is drained on a background thread to avoid a pipe deadlock.
* If `ffmpeg` is missing or the transcode fails, the turn degrades safely to its text fallback
  rather than sending an undecodable payload to Whisper.

## Gnani Speech Adapters — Opt-in

`GnaniAudioTranscriptionProvider` is the priority-40 cloud tier behind the governed
transcription cascade. It sends audio only when `amsha.stt.gnani.enabled=true`, after
the local and OMLX tiers report `UNSUPPORTED`. The adapter uses a supported request or channel hint when available; otherwise it
uses the code default `en-IN` (Indian English), which operators may override with
another supported code. It measures duration locally and caps duration at 30 seconds,
and treats every upstream HTTP 400 as `FAILED` until Gnani publishes stable error
discriminators. The configured API key and HTTP bodies are never logged.

`GnaniSpeechSynthesisProvider` is selected through `amsha.tts.provider=gnani` and
returns WAV only. Set `amsha.tts.enabled=true` as well; both switches are required.
Version 1 uses Timbre v3.0 so each allowlisted voice supports all 10 documented
languages. Both adapters remain disabled by default while API key scope and data
processing terms are open.

| Property | Environment variable | Default |
|---|---|---|
| `amsha.gnani.api-key` | `AMSHA_GNANI_API_KEY` | *(empty)* |
| `amsha.stt.gnani.enabled` | `AMSHA_STT_GNANI_ENABLED` | `false` |
| `amsha.stt.gnani.default-language` | `AMSHA_STT_GNANI_DEFAULT_LANGUAGE` | `en-IN` (Indian English; override with a supported code) |
| `amsha.stt.gnani.max-duration` | `AMSHA_STT_GNANI_MAX_DURATION` | `PT30S` |
| `amsha.tts.gnani.model` | `AMSHA_TTS_GNANI_MODEL` | `timbre-v3.0` |
| `amsha.tts.gnani.voice` | `AMSHA_TTS_GNANI_VOICE` | `Shivani` |

## Governed Speech Synthesis (P8 / Issue 12) — DISABLED by default

`OmlxSpeechSynthesisProvider` implements the `SpeechSynthesisPort` execution seam for
text-to-speech served by OMLX (Qwen3-TTS). It is output-only: never a chat-model
capability, never an `AUDIO` route profile, never persisted, and never shares routing
with P7 audio input. V1 only emits the directly verified WAV format from the OMLX
Qwen3-TTS model — any other `response_format` is reported `UNSUPPORTED` rather than
attempted. All egress is governed through `GovernedTransportService` with HITL never
bypassed. The actor is resolved from the session (`WorkspaceContext.getUserIdOrUnknown()`).

| Property | Environment variable | Default |
|---|---|---|
| `amsha.tts.provider` | `AMSHA_TTS_PROVIDER` | `omlx` |
| `amsha.tts.enabled` | `AMSHA_TTS_ENABLED` | `false` |
| `amsha.tts.model` | `AMSHA_TTS_MODEL` | `Qwen3-TTS-12Hz-1.7B-Base-bf16` |
| `amsha.tts.voice` | `AMSHA_TTS_VOICE` | *(empty — field omitted)* |
| `amsha.tts.response-format` | `AMSHA_TTS_RESPONSE_FORMAT` | `wav` |
| `amsha.tts.timeout` | `AMSHA_TTS_TIMEOUT` | `PT120S` |
| `amsha.tts.max-input-chars` | `AMSHA_TTS_MAX_INPUT_CHARS` | `1200` |
| `amsha.tts.max-bytes` | `AMSHA_TTS_MAX_BYTES` | `8388608` |
| `amsha.tts.max-concurrent` | `AMSHA_TTS_MAX_CONCURRENT` | `1` |
| `amsha.tts.default-output-mode` | `AMSHA_TTS_DEFAULT_OUTPUT_MODE` | `TEXT_ONLY` |

The OMLX API key and base URL are reused from `AMSHA_OMLX_API_KEY` and
`AMSHA_OMLX_BASE_URL` (the key is optional — `not-needed` is treated as absent). The
response stream is capped at `amsha.tts.max-bytes` by `BoundedResponseSubscriber`, which
cancels upstream the moment the bound is exceeded (no post-allocation check). The
`default-output-mode` and `max-input-chars` keys are consumed by `amsha-core`
(`SpeechOutputPolicy` / `SpeechReplyService`); see the core module README for the
resolution precedence.
