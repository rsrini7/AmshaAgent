# Gnani AI Integration Research

- **Status**: Evaluated
- **Retrieved**: 2026-10-20
- **Purpose**: Record evaluated Gnani capabilities, source conflicts, and negative knowledge for future work.
- **Active plan**: [Gnani AI integration proposal](../proposals/Gnani-AI-Integration-Plan-2026-10-20.md)
- **Architecture decision**: [Gnani AI integration ADR](../adr/providers/ADR-Gnani-AI-Provider.md)

## Findings

Gnani has speech, voice, and Agent Builder surfaces. The nearest fit for Amsha is speech input/output through its existing transcription and synthesis ports. Agent Builder is a separate platform API. Public materials advertise Evon and Warp, but the reviewed sources do not establish a generally available hosted Evon chat-completions contract or a supported Warp API contract.

## Account-confirmed speech contract

On 2026-10-20, the authenticated Gnani dashboard and its linked API docs were inspected read-only.

| Capability | Account-linked contract | Account selection or observation |
|---|---|---|
| STT REST | POST https://api.vachana.ai/stt/v3; X-API-Key-ID; required language_code | Prisma v2.5 |
| TTS REST | POST https://api.vachana.ai/api/v1/tts/inference; X-API-Key-ID; binary audio; language=auto is supported | timbre-v2.5 or timbre-v3.0 |
| STT duration | The overview, request parameter, OpenAPI path, and 400 example disagree; see the verbatim account-linked excerpts in [ADR §3](../adr/providers/ADR-Gnani-AI-Provider.md). | Unresolved; follow the canonical decision in [ADR §2.2](../adr/providers/ADR-Gnani-AI-Provider.md) until Gnani confirms the operative limit. |
| STT/TTS 400 errors | `error.type` is the generic `INVALID_REQUEST_ERROR`; `message` is free text. No stable size, format, or language discriminator is published. | Preflight locally; treat upstream 400s as FAILED and do not parse messages ([ADR §2.4](../adr/providers/ADR-Gnani-AI-Provider.md)) |
| Authenticated-console limits | 60 requests/minute for STT REST, batch STT, and TTS; 20 concurrent STT WebSocket sessions | Account-specific observations, not public global quotas |
| API keys | Console says up to three keys can be created for STT/TTS; `X-API-Key-ID` is the credential header | Secret supplied through `AMSHA_GNANI_API_KEY`; cross-service key scope unverified |
| Pricing | Credit-based page with per-unit prices | Rates omitted because the EULA treats pricing information as confidential |

The public [privacy policy](https://www.gnani.ai/privacy) does not specify audio or transcript payload retention. No key was created or revealed, no purchase was made, and no live API request was sent. Production egress remains gated until API-specific processing/retention terms and key scope are confirmed.

## First-party contract discrepancy

The account-linked API docs use api.vachana.ai, X-API-Key-ID, and the model IDs timbre-v2.5/timbre-v3.0. The [English TTS marketing page](https://www.gnani.ai/text-to-speech) shows api.gnani.ai, Bearer authentication, and timbre-2.5. The page is undated as a migration notice, so this is recorded as a conflicting sample, not a newer contract.

## Possible integrations

| Surface | Documented capability | Potential Amsha use | Evaluation |
|---|---|---|---|
| Prisma REST STT | Uploaded audio, required language_code, duration limit discrepancy | AudioTranscriptionPort cloud-tier fallback through governed media | Initial proposal; strict language and egress gates |
| Timbre REST TTS | Model, voice, language, binary output; several output formats | SpeechSynthesisPort with WAV first | Initial proposal; explicit CDI provider selection required |
| Realtime STT/TTS | WebSocket STT with PCM/VAD/segment events; TTS SSE and WebSocket | Streaming ports with cancellation, backpressure, and channel lifecycle | Follow-on |
| Batch STT | Asynchronous jobs and webhooks; up to four-hour files, diarization, denoising, word bias, candidate languages; narrower language coverage than REST/realtime | Job workflow with status and retention handling | Separate proposal |
| Voice catalog and cloning | Voice discovery and cloning endpoints; Timbre models differ in voice/language coverage | Read-only catalog, then consent-based cloning controls | Separate proposal |
| Agent Builder | Agent/configuration and FAQ management, conversation logs/stats/audio, test-call trigger, webhooks, embeddable chat-widget configuration | Separate governed connector; start with read-only analytics | Separate proposal |
| Evon | Open-weight model card; hosted API waitlist | Evaluate self-hosted serving, license, resources, tool calling, and quality | Research only |
| Warp | Marketed speech-to-speech product; no supported API contract found in reviewed docs | Revisit when Gnani publishes a supported contract and Amsha has a streaming seam | Deferred |

The [TTS REST docs](https://docs.gnani.ai/api/TTS/tts-inference) also describe multiple audio formats and v3 expressive tags. The current Amsha result contract accepts WAV only, so these do not change first-release scope.

## Comparison with existing Amsha paths

| Existing path | What it provides | Why Gnani is not a direct replacement |
|---|---|---|
| Audio-capable chat model (for example Gemini) | Already integrated; audio can be passed to a multimodal model | Couples transcription to a reasoning model and inherits that model's cost/retention profile; less deterministic STT control |
| OMLX Whisper | Existing `AudioTranscriptionPort` tier, ahead of Gnani; avoids a new vendor | Depends on the OMLX runtime and does not add Gnani's TTS or Indian-language voice catalog |
| Local ONNX bundle | In-process, no cloud egress; default bundle is `whisper-tiny-en-v1` | English-only by default; a multilingual bundle upgrade is separate work and adds no TTS |
| Defer | Avoids a new credential and retention review | Leaves non-English and longer audio dependent on the existing OMLX or audio-model paths |

Gnani is therefore evaluated as an opt-in cloud-tier STT fallback plus an opt-in TTS provider, gated on retention and key-scope confirmation. It is not a replacement for the existing local/OMLX tiers or the audio-capable chat-model fallback.

## Primary sources

- [Gnani API introduction](https://docs.gnani.ai/api/introduction/introduction)
- [STT REST](https://docs.gnani.ai/api/STT/speech-to-text)
- [STT WebSocket](https://docs.gnani.ai/api/STT/stt-websocket)
- [STT batch](https://docs.gnani.ai/api/STT/stt-batch)
- [TTS REST](https://docs.gnani.ai/api/TTS/tts-inference)
- [TTS streaming](https://docs.gnani.ai/api/TTS/tts-sse)
- [TTS WebSocket](https://docs.gnani.ai/api/TTS/tts-websocket)
- [TTS audio tags](https://docs.gnani.ai/api/TTS/tts-audio-tags)
- [Available voices](https://docs.gnani.ai/api/TTS/available-voices)
- [Voice-cloning embeddings](https://docs.gnani.ai/api/VC/voice-clone-embeddings)
- [Agent Builder Platform API](https://docs.gnani.ai/Platform/platform-introduction)
- [English TTS product/API page](https://www.gnani.ai/text-to-speech)
- [Python SDK linked from Gnani docs](https://github.com/Gnani-AI-Mintlify/Gnani-Vachana) (provenance not independently confirmed)
- [LiveKit plugin linked from Gnani docs](https://github.com/Gnani-AI-Mintlify/livekit-plugins-gnani) (provenance not independently confirmed)
- [Evon model card](https://huggingface.co/gnani/gnani-evon-v3.3-30B-A3B)
- [Evon hosted API waitlist](https://www.gnani.ai/evon-waitlist)
- [Privacy policy](https://www.gnani.ai/privacy)
- [EULA](https://www.gnani.ai/legal/eula)
