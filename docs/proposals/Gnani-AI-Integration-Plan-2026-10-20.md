# Gnani AI Speech Integration Plan

- **Status**: Implemented behind disabled-by-default gates; upstream data terms, key scope, duration contract, and 400 error discriminators unresolved
- **Updated**: 2026-10-20
- **ADR**: [Gnani AI speech integration](../adr/providers/ADR-Gnani-AI-Provider.md)
- **Research**: [Gnani AI integration research](../research/Gnani-AI-Integration-Research-2026-10-20.md)
- **Local issue tracker**: .scratch/gnani-ai-integration/ (ignored by Git)

## Objective

Add opt-in Gnani speech execution to Amsha through AudioTranscriptionPort and SpeechSynthesisPort. Do not add a Gnani reasoning or chat provider. Preserve governed media input, explicit cloud egress, deterministic CDI selection, and safe text fallback.

## Current upstream contract

- STT: POST https://api.vachana.ai/stt/v3, X-API-Key-ID, Prisma v2.5, required `language_code`.
- TTS: POST https://api.vachana.ai/api/v1/tts/inference, X-API-Key-ID, model timbre-v2.5 or timbre-v3.0.
- STT duration: follow the canonical decision in [ADR §2.2](../adr/providers/ADR-Gnani-AI-Provider.md). The conflicting account-linked statements and verbatim excerpts are recorded in ADR §3; do not change the configured bound until Gnani confirms the operative limit.
- TTS language is selected from its documented enum, including `auto`; STT has no auto language option and requires a code from its supported enum.
- STT/TTS 400 responses expose only a generic `INVALID_REQUEST_ERROR` plus a free-text message. No stable size, format, or language discriminator is published; upstream 400s fail closed.
- The account-linked docs govern host/auth/model choices for this account. The marketing-page sample remains a separately documented conflict.

No API-specific audio/transcript retention terms or cross-service key scope have been confirmed. Do not enable production egress until both are resolved.

## Configuration and secrets

- `AMSHA_GNANI_API_KEY`: secret credential sent as `X-API-Key-ID`; required when Gnani is enabled; never log, echo, or place in metric tags; redact the header from errors. Whether one key serves both STT and TTS remains an open gate.
- `amsha.stt.gnani.enabled` (`AMSHA_STT_GNANI_ENABLED`): default `false`; the explicit cloud-tier egress switch.
- `amsha.stt.gnani.default-language` (`AMSHA_STT_GNANI_DEFAULT_LANGUAGE`): default `en-IN` (Indian English); may be overridden with a supported STT language code.
- `amsha.stt.gnani.max-duration` (`AMSHA_STT_GNANI_MAX_DURATION`): default `PT30S`; applied to measured duration, not the sender-declared value.
- `amsha.stt.max-bytes` (`AMSHA_STT_MAX_BYTES`): remains the hard byte cap and defense-in-depth bound.
- `amsha.tts.provider` (`AMSHA_TTS_PROVIDER`): default `omlx`; selects exactly one `SpeechSynthesisPort` by `providerId()`.
- `AMSHA_TTS_ENABLED`: remains `false` by default; provider selection does not enable speech output.

Document every new property in `application.yaml`, `.env.example`, `docs/CONFIGURATION.md`, `ConfigKeys`, and the relevant module README.

## Decisions to carry through implementation

### STT contracts and fallback

- Preserve provider order: local ONNX 100, OMLX 50, Gnani 40.
- Treat Gnani as a cloud-tier fallback. With current CompositeAudioTranscriptionPort semantics, only UNSUPPORTED proceeds by default. FAILED and TIMEOUT stop unless failure fallback is explicitly enabled. TOO_LARGE is terminal.
- Keep failure fallback disabled by default and require `amsha.stt.gnani.enabled=true` as the explicit cloud-tier egress switch. It defaults to false.
- Preflight before egress: MIME allowlist (WAV, MP3, OGG, FLAC, AAC, M4A), STT language enum, byte cap, and measured duration. Unsupported format or language returns UNSUPPORTED before any request is sent. A local over-cap check returns TOO_LARGE.
- Measure actual duration from the governed audio bytes or temp file using the existing FFmpeg/PCM path (`amsha.stt.ffmpeg-path`) or an equivalent bounded probe. `IncomingVoice.getDuration()` is only a declared hint. A declared value over 30 seconds may reject early; a value at or under 30 seconds never authorizes egress. If measurement fails, return FAILED with a payload-safe reason and do not send.
- Do not classify upstream HTTP 400s as TOO_LARGE or UNSUPPORTED. The published schema only exposes generic `INVALID_REQUEST_ERROR` plus free text; treat upstream 400s as FAILED and never parse or log the raw body. Add stable-code mapping only if Gnani publishes documented discriminators.
- Set `language_code` deterministically. Use a supported request/user/channel hint when present; otherwise use the code default `en-IN` (Indian English), overridable through `amsha.stt.gnani.default-language`. Reject an unsupported language before egress. Until a request or channel language hint is wired, clips without a hint are submitted as Indian English and may be transcribed incorrectly when spoken in another language.
- Add optional `declaredDuration` to `AudioTranscriptionRequest`. `TelegramMediaResolver.resolveVoice` writes `IncomingVoice.getDuration()` as `declared_duration_seconds` into `MediaAttachment.metadata`; `resolveMedia` must accept metadata and update all four callers (`resolvePhoto`, `resolveDocument`, `resolveAudio`, `resolveVoice`). `AttachmentContentMapper` validates and forwards the hint. `DownloadedMedia` stays the governed bytes/file container. Measurement remains mandatory when the hint is absent.
- Validate outbound STT language against Gnani's current OpenAPI enum. For result metadata, either widen `AudioTranscriptionResult` to accept RFC 5646/BCP-47 region subtags such as `hi-IN`, or normalize to a language-only tag before construction; choose one and test `hi-IN`.

### TTS contracts and selection

- Add `providerId()` to the `SpeechSynthesisPort` interface and implement it in each adapter, with OMLX returning `omlx` and Gnani returning `gnani`.
- Add `amsha.tts.provider` with default `omlx`. Keep `amsha.tts.enabled` false by default; provider selection alone does not turn speech on.
- Update `SpeechReplyService` to enumerate `speechPort.stream().toList()` and select exactly one matching provider ID. Unknown provider, absent match, or duplicate match must preserve text fallback.
- Land the selection seam before adding the Gnani CDI bean. Do not use `Instance.get()` or `isResolvable()` to choose between multiple providers.
- Keep `SpeechSynthesisRequest` unchanged. Detect language from text script and resolve an allowlisted TTS language; use `auto` when uncertain. Script detection cannot distinguish Devanagari languages, so ambiguous script uses `auto` plus a configured compatible voice rather than inferring Hindi versus Marathi. Map Amsha's generic voice preference to a verified, model-compatible Gnani voice, then use a configured fallback voice or text fallback.
- Return WAV in the initial release. Bound request text and audio response size, and keep request text, credentials, and response bytes out of logs and metric tags.

### User-visible failure behavior

- Update `AttachmentContentMapper` to branch on `TOO_LARGE` (including `audio_duration_exceeded`/`audio_too_large`) and render a specific “audio exceeds the configured duration or size limit” message instead of the generic `fallbackUnsupported` guidance; do not treat that message as a transcript.
- Other audio transcription failures should render neutral transcription-failure text rather than recommend selecting a different model when a transcription attempt already failed.
- Keep TOO_LARGE terminal and do not cascade it to another provider.

## Delivery sequence

| Iteration | Work | Exit condition |
|---|---|---|
| 0 | Confirm API contract, duration limit, key scope, data terms, and stable 400 error codes | Gnani confirms which STT limit applies; API-specific processing/retention, key scope, and any stable error discriminators are recorded |
| 1 | Resolve request/result language validation, declared/measured duration plumbing, TTS provider IDs, fallback text, and configuration | Tests cover required STT language, `hi-IN` result tags, lying declared duration, measured 30-second cap, unsupported preflight, upstream 400 fail-closed, TOO_LARGE-specific text, and provider selection |
| 2 | Add Prisma REST adapter | Governed transport verifies endpoint, auth-header redaction, bounds, payload-safe errors, measured duration, and cloud-tier UNSUPPORTED-only cascade |
| 3 | Add Timbre REST adapter | Controlled transport verifies language/voice mapping, provider selection, bounded WAV, and text fallback |
| 4 | Wire and govern release | CDI/JVM/native paths, reflection, docs, and approved smoke evidence pass. Update `docs/Architecture_Invariants.md` §25.1a for the Gnani priority, terminal TOO_LARGE behavior, and temporary-audio release obligation, plus `docs/CONFIGURATION.md`, `application.yaml`, `.env.example`, `ConfigKeys`, and module READMEs |

## Open gates

1. Gnani confirms whether 30 or 60 seconds is the operative STT REST limit.
2. Confirm API-specific audio/transcript processing and retention terms and data residency if applicable.
3. Confirm whether speech keys are shared between STT and TTS.
4. Confirm the current exact STT and TTS language/voice allowlists against the account-linked API schema.
5. Confirm whether Gnani publishes stable 400 error discriminators; until then, upstream 400s are FAILED.
6. Any desire to route local TOO_LARGE, FAILED, or TIMEOUT results to Gnani requires a separate explicit egress decision.
