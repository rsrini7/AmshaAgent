# ADR: Gnani AI Speech Provider

- **Status**: Proposed
- **Implementation status**: Opt-in STT/TTS adapters are implemented behind disabled-by-default gates; production egress remains blocked on retention and key-scope confirmation.
- **Date**: 2026-10-20
- **Decision owners**: Amsha maintainers
- **Feature slug**: gnani-ai-integration
- **Issue tracker**: .scratch/gnani-ai-integration/ (local and gitignored)
- **Research**: [Gnani AI integration research](../../research/Gnani-AI-Integration-Research-2026-10-20.md)
- **Implementation proposal**: [Gnani AI integration plan](../../proposals/Gnani-AI-Integration-Plan-2026-10-20.md)
- **Related**: [Sarvam provider ADR](ADR-Sarvam-Provider.md), [LangChain4j capability adoption ADR](../architecture/ADR-LangChain4j-Capability-Adoption-and-Amsha-Boundaries.md), [Architecture Invariants §25.1a](../../Architecture_Invariants.md#251a-speech-provider-boundaries)

## 1. Context

Gnani offers STT and TTS APIs, realtime and batch speech products, voice features, and a separate Agent Builder Platform API. Amsha already has channel-neutral AudioTranscriptionPort and SpeechSynthesisPort contracts, a local Whisper adapter, OMLX adapters, governed media input, and optional final-answer speech output.

The initial integration should add bounded speech adapters behind those existing ports. It must not register Gnani as a chat or reasoning provider. Account-linked docs confirm the speech hosts, auth header, and models, but three contract details remain unresolved: the STT duration limit conflicts within the public REST page, API-specific payload retention is not confirmed, and cross-service key scope is not confirmed.

## 2. Proposed decisions

1. **Use the account-linked API contract.** STT is POST https://api.vachana.ai/stt/v3 with X-API-Key-ID and Prisma v2.5. TTS is POST https://api.vachana.ai/api/v1/tts/inference with X-API-Key-ID and model IDs timbre-v2.5 or timbre-v3.0. Treat the authenticated account's linked docs as authoritative for host, auth, and model selection unless Gnani confirms migration. All Gnani calls must use the governed outbound transport and a typed purpose; do not instantiate a direct HTTP client. `X-API-Key-ID` is a secret credential: supply it through `AMSHA_GNANI_API_KEY`, never log or echo it, and redact it from errors and metrics. Whether one key may serve both STT and TTS remains an open gate.
2. **Enforce a conservative measured STT duration bound.** The public STT page is internally inconsistent: it states both 60 and 30 seconds (quotes in §3). Until Gnani confirms the operative limit, enforce 30 seconds on the actual decoded duration of the governed audio, using the existing FFmpeg/PCM path or an equivalent bounded probe. The cap is `amsha.stt.gnani.max-duration` (`AMSHA_STT_GNANI_MAX_DURATION`), default `PT30S`; do not raise it without confirmation. `IncomingVoice.getDuration()` is only a sender-declared hint: it may reject early, but it must never authorize egress. If duration cannot be measured, fail closed and do not send. Do not send audio whose measured duration exceeds 30 seconds. The plan does not change CompositeAudioTranscriptionPort's terminal TOO_LARGE behavior.
3. **Keep Gnani as an explicit cloud-tier fallback.** Preserve current ordering: local ONNX priority 100, OMLX 50, Gnani 40. CompositeAudioTranscriptionPort proceeds to the next provider for UNSUPPORTED only by default; FAILED and TIMEOUT stop unless failure fallback is explicitly enabled, and TOO_LARGE is always terminal. Keep `amsha.stt.gnani.enabled` false by default; enabling it is the explicit cloud-tier egress switch. This delivery does not make Gnani a general failure fallback. If local and OMLX are both disabled, enabling Gnani makes it the only route; that is an explicit operator choice, not a default. Any change that sends local TOO_LARGE, FAILED, or TIMEOUT audio to a cloud provider requires a separate explicit egress decision.
4. **Fail closed on upstream HTTP 400 responses.** Preflight the supported MIME allowlist (WAV, MP3, OGG, FLAC, AAC, M4A), the language allowlist, the measured duration, and the byte cap before egress. Return UNSUPPORTED before egress for an unsupported format or language, TOO_LARGE for the local measured duration or size cap, and FAILED for malformed or unrecognized requests. The published 400 schema exposes only a generic `INVALID_REQUEST_ERROR` plus a free-text `message`; do not parse that text. Until Gnani publishes stable error discriminators, treat every upstream 400 as FAILED. Never log raw error bodies or the auth header. Do not classify upstream 400s as TOO_LARGE or UNSUPPORTED; doing so would either produce a misleading terminal message or cause additional egress.
5. **Provide a deterministic STT language code.** Gnani requires `language_code` from its fixed supported-language enum and does not document STT auto detection. `AudioTranscriptionRequest.languageHint` is currently passed as null by `AttachmentContentMapper`. Prefer a validated explicit request or channel/user language hint; when absent, use the code default `en-IN` (Indian English), which an operator may override with a supported `amsha.stt.gnani.default-language`. Reject an unsupported language before egress. The fallback is an explicit compatibility decision: until a request or channel language hint is wired, clips without a hint are submitted as Indian English and may be transcribed incorrectly when spoken in another language.
6. **Separate provider input enums from result language tags.** Validate outbound STT `language_code` against Gnani's current OpenAPI enum. For transcription result metadata, either widen `AudioTranscriptionResult` to validate RFC 5646/BCP-47 tags including region subtags such as `hi-IN`, or normalize to a language-only tag before construction. Choose one representation in iteration 1 and test `hi-IN`. Do not use a permissive token regex as a BCP-47 validator.
7. **Carry declared duration as a hint and measure enforcement separately.** `TelegramMediaResolver.resolveVoice` should store `IncomingVoice.getDuration()` as non-secret descriptive data under `declared_duration_seconds` in `MediaAttachment.metadata`. `resolveMedia` must accept that metadata; update all four callers (`resolvePhoto`, `resolveDocument`, `resolveAudio`, `resolveVoice`). `AttachmentContentMapper` should validate and pass the optional `declaredDuration` into `AudioTranscriptionRequest`, and the adapter must measure the actual duration before egress. A declared value over 30 seconds may reject early; a value at or under 30 seconds never authorizes a clip. `DownloadedMedia` remains the governed byte/file container and does not need a metadata field. Other channels may omit duration; those requests still require measurement.
8. **Select TTS explicitly before adding a second bean.** Add `providerId()` to the `SpeechSynthesisPort` interface and implement it in each adapter. Configure `amsha.tts.provider` with default `omlx`. `SpeechReplyService` must enumerate `speechPort.stream().toList()` and select exactly one matching provider ID. Unknown IDs or zero/multiple matches preserve text fallback. Land this seam before registering the Gnani TTS bean; do not rely on `Instance.get()` or `isResolvable()` when multiple implementations exist.
9. **Resolve TTS language and voice inside the Gnani adapter.** `SpeechSynthesisRequest` has no language field. Detect text language from script for voice selection and send a documented TTS language value: `auto` when uncertain, otherwise an allowlisted code from the TTS enum (including `hi-en` where supported). Script detection cannot distinguish Devanagari languages; when the script is ambiguous, send `auto` and use a configured model-compatible voice rather than inferring Hindi versus Marathi from script alone. Use only a validated provider language allowlist and model-compatible voice IDs. Timbre v2.5 voices are language-specific; Timbre v3.0 has broader multilingual voice coverage. If no valid voice mapping or configured fallback exists, use the existing safe text fallback.
10. **Keep initial synthesis synchronous and bounded.** Use TTS REST and WAV output because the current `SpeechSynthesisResult` accepts `audio/wav` only. Keep `amsha.tts.enabled` false by default; selecting OMLX as the provider default does not enable speech output.

## 3. Evidence and unresolved contract details

The account-linked [STT REST docs](https://docs.gnani.ai/api/STT/speech-to-text) document the Vachana host, X-API-Key-ID, Prisma v2.5, and required `language_code`. Retrieved 2026-10-20, the page states both limits:

- Overview: "Quick transcription of audio clips up to 60 seconds via HTTP."
- Request parameter: "Maximum duration: 60 seconds (ideal ≤ 30 s)."
- OpenAPI path description: "Maximum duration: 30 seconds (Ideal duration is 30 seconds)."
- 400 example: "Audio duration exceeds maximum limit of 60 seconds, while the ideal duration is 30 seconds."

The canonical decision is the 30-second measured cap in §2.2; the plan and research should link here rather than restating a different limit. The same page documents the supported language enum: `bn-IN`, `en-IN`, `gu-IN`, `hi-IN`, `kn-IN`, `ml-IN`, `mr-IN`, `pa-IN`, `ta-IN`, and `te-IN`.

The STT and TTS 400 schemas expose `error.type` with the generic value `INVALID_REQUEST_ERROR` and a free-text `error.message`; they do not publish stable size, format, or language discriminators. Decision 4 therefore preflights locally and treats upstream 400s as FAILED until Gnani confirms stable codes.

The account-linked [TTS REST docs](https://docs.gnani.ai/api/TTS/tts-inference) document the Vachana host, X-API-Key-ID, Timbre IDs timbre-v2.5 and timbre-v3.0, and binary audio output. The [voice catalog](https://docs.gnani.ai/api/TTS/available-voices) documents `language=auto` and model-specific voice support.

The [English TTS marketing page](https://www.gnani.ai/text-to-speech) shows api.gnani.ai, Bearer authentication, and timbre-2.5. No dated evidence establishes that sample as newer. Keep it as a conflicting first-party sample, separate from the account-linked contract.

The authenticated account lists 60 requests per minute for STT REST, batch STT, and TTS, and 20 concurrent STT WebSocket sessions. These are account observations, not public service-wide limits. The pricing page is credit-based and contains per-unit rates that are not reproduced because the Gnani EULA treats pricing information as confidential. The [public privacy policy](https://www.gnani.ai/privacy) does not specify audio/transcript payload retention. Confirm API-specific processing and retention terms, and whether one speech key can serve both STT and TTS, before production egress.

## 4. Alternatives considered

- **Existing audio-capable chat model (for example Gemini).** Amsha already offers this path and the current fallback text recommends it. It avoids a new vendor and credential, but couples transcription to a reasoning model, gives less deterministic STT control, and carries its own cost and retention profile. It remains a valid fallback, not a reason to add Gnani as a replacement.
- **Existing OMLX Whisper tier.** The OMLX adapter is already behind `AudioTranscriptionPort` and stays ahead of Gnani. It avoids a new vendor but depends on the OMLX runtime and does not add Gnani's TTS or Indian-language catalog. Keep it as the preferred tier when enabled.
- **Upgrade the local ONNX bundle from `whisper-tiny-en-v1` to a multilingual bundle.** This removes cloud egress and a new credential, but increases model size, runtime resources, and bundle/native work, and does not provide TTS. Evaluate as a separate improvement rather than blocking this decision.
- **Defer the integration.** This avoids a new credential, retention review, and vendor dependency, but leaves non-English and longer audio dependent on the existing OMLX or audio-model paths. This is the status quo.

Gnani is justified only as an opt-in cloud-tier STT fallback plus an opt-in TTS provider, gated on retention and key-scope confirmation, because it adds Indian-language coverage and a voice catalog without changing the reasoning model. If the gates do not clear, defer.

## 5. Scope and deferred options

The first delivery excludes Agent Builder CRUD or call triggering, voice cloning, STT/TTS streaming, batch STT, Warp speech-to-speech, and hosted Evon inference. Revisit these as separate proposals after confirming contracts, user need, data handling, and the appropriate Amsha boundary.

## 6. Consequences

- Gnani can add speech input/output without changing Amsha's reasoning model or channel contracts.
- Gnani STT is a cloud-tier fallback for UNSUPPORTED results only by default; locally measured clips over 30 seconds remain terminal until the duration contract is clarified.
- A locally measured clip beyond the configured duration or size limit returns `TOO_LARGE` with a specific limit message, not the generic unsupported-modality guidance. Other upstream 400s fail closed as `FAILED` without cascading.
- The local ONNX bundle default is English-only. With Gnani as the last tier and a 30-second cap, non-English clips between 31 and 60 seconds currently fail unless OMLX is enabled. This is an accepted first-release limitation until the duration contract is confirmed.
- STT requires a deterministic language source. The initial deployment must explicitly choose a default if the request carries no language hint.
- Declared duration travels on MediaAttachment metadata and AudioTranscriptionRequest as a hint; measured duration controls egress, while DownloadedMedia remains focused on governed bytes and cleanup.
- TTS requires provider IDs and explicit selection before OMLX and Gnani can coexist.
- TTS needs provider-local language and voice mapping and returns WAV in the initial release. Ambiguous scripts use `auto` plus a configured compatible voice.
- Production egress remains gated by data-processing/retention terms, key-scope confirmation, and a configured `AMSHA_GNANI_API_KEY`.
- Native-image reflection, payload bounds, deterministic selection, egress policy, and safe failure fallback remain release requirements.
