# amsha-llm-providers

Houses all 21 built-in concrete LLM provider factories, LangChain4j concrete SDKs (`quarkus-langchain4j-openai`, `quarkus-langchain4j-anthropic`, `quarkus-langchain4j-ollama`, `quarkus-langchain4j-ai-gemini`), custom HTTP/SSE response wrappers, token resolvers, and `ChatModelFactory`.

`amsha-llm-providers` implements the contracts defined in `amsha-llm-provider-api`. `amsha-core` consumes providers decoupled via `ChatModelCreationPort` and CDI `Instance<T>` multi-bean discovery.

## Supported Providers

| Provider | Provider ID | Default Model | Default Base URL |
|---|---|---|---|
| Groq | `groq` | `meta-llama/llama-4-scout-17b-16e-instruct` | `https://api.groq.com/openai/v1` |
| OpenAI | `openai` | `gpt-4o-mini` | `https://api.openai.com/v1` |
| Ollama | `ollama` | `mistral-small:latest` | `http://localhost:11434` |
| OpenRouter | `openrouter` | `openai/gpt-oss-20b:free` | `https://openrouter.ai/api/v1` |
| NVIDIA | `nvidia` | `bytedance/seed-oss-36b-instruct` | `https://integrate.api.nvidia.com/v1` |
| Cerebras | `cerebras` | `qwen-3-235b-a22b-instruct-2507` | `https://api.cerebras.ai/v1` |
| Sarvam AI | `sarvam` | `sarvam-105b` | `https://api.sarvam.ai/v1` |
| OpenCode | `opencode` | `deepseek-v4-flash-free` | `https://opencode.ai/zen/v1` |
| Hugging Face | `huggingface` | `meta-llama/Llama-3.3-70B-Instruct` | `https://router.huggingface.co/v1` |
| Mistral AI | `mistral` | `codestral-latest` | `https://api.mistral.ai/v1` |
| Cloudflare Workers AI | `cloudflare` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | `https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1` |
| Cohere | `cohere` | `command-r-plus-08-2024` | `https://api.cohere.ai/compatibility/v1` |
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
