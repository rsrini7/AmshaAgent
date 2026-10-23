package com.amsha.common.config;

/**
 * Canonical names for Amsha's environment-variable / system-property configuration keys.
 * <p>
 * These keys are the contract between the running process, the operator's {@code .env}, and the
 * deployment manifests. Several were historically retyped independently in more than one module —
 * {@code AMSHA_DATA_DIR} was declared in three places, {@code AMSHA_ONNX_MODEL_ROOT} in two, and
 * the provider credential keys appeared both in the provider factories and in the
 * setup/validation tables. A single drifted copy silently reads the wrong key and falls back to a
 * default: the provider is not selected, the credential is not found, or the data directory is not
 * honoured. Reference these constants instead of retyping the literals.
 * <p>
 * Precedence for every key is <strong>system property before environment variable</strong>
 * (MicroProfile ordinals); callers read both spellings.
 */
public final class ConfigKeys {

    private ConfigKeys() {}

    // -- LLM selection ----------------------------------------------------

    /** Selected LLM provider id (read as both a system property and an environment variable). */
    public static final String LLM_PROVIDER = "AMSHA_LLM_PROVIDER";

    /** Environment variable controlling first-run system-secret seeding. */
    public static final String AUTO_SEED_SECRETS_ENV = "AMSHA_AUTO_SEED_SECRETS";

    /** MicroProfile property controlling first-run system-secret seeding. */
    public static final String AUTO_SEED_SECRETS_PROPERTY = "amsha.auto-seed-secrets";

    /** Environment variable enabling the Telegram channel. */
    public static final String TELEGRAM_ENABLED = "AMSHA_TELEGRAM_ENABLED";

    /** MicroProfile property enabling the Telegram channel. */
    public static final String TELEGRAM_ENABLED_PROPERTY = "amsha.channel.telegram.enabled";

    /** Environment variable containing comma-separated numeric Telegram user IDs. */
    public static final String TELEGRAM_ALLOWED_USER_IDS_ENV = "AMSHA_TELEGRAM_ALLOWED_USER_IDS";
    /** MicroProfile property containing numeric Telegram user IDs. */
    public static final String TELEGRAM_ALLOWED_USER_IDS_PROPERTY = "amsha.channel.telegram.allowed-user-ids";
    /** Environment variable containing the deprecated Telegram username allowlist. */
    public static final String TELEGRAM_ALLOWED_USERS_ENV = "AMSHA_TELEGRAM_ALLOWED_USERS";
    /** MicroProfile property containing the deprecated Telegram username allowlist. */
    public static final String TELEGRAM_ALLOWED_USERS_PROPERTY = "amsha.channel.telegram.allowed-users";

    // -- Provider credentials / endpoints ---------------------------------

    /** Cloudflare account id used to build the Workers AI endpoint. */
    public static final String CLOUDFLARE_ACCOUNT_ID = "AMSHA_CLOUDFLARE_ACCOUNT_ID";

    /** Cloudflare Workers AI API token. */
    public static final String CLOUDFLARE_API_KEY = "AMSHA_CLOUDFLARE_API_KEY";

    /** Cohere API key. */
    public static final String COHERE_API_KEY = "AMSHA_COHERE_API_KEY";

    /** Kimchi API key (CLI/OAuth-backed provider). */
    public static final String KIMCHI_API_KEY = "AMSHA_KIMCHI_API_KEY";

    /** Claude Code API key (CLI/OAuth-backed provider). */
    public static final String CLAUDE_CODE_API_KEY = "AMSHA_CLAUDE_CODE_API_KEY";

    /** Antigravity (agy) API key. */
    public static final String AGY_API_KEY = "AMSHA_AGY_API_KEY";

    /** Override for the Foundry CLI executable. */
    public static final String FOUNDRY_EXE = "AMSHA_FOUNDRY_EXE";

    // -- Primary LLM-slot provider API keys -------------------------------
    // These are the env-var names the provider factories read AND the names the setup wizard,
    // env-file validator, and configuration-provenance table present. A drifted copy in any one of
    // those places asks the operator for a variable the runtime never reads.

    /** Groq Cloud API key. */
    public static final String GROQ_API_KEY = "AMSHA_GROQ_API_KEY";

    /** OpenRouter API key. */
    public static final String OPENROUTER_API_KEY = "AMSHA_OPENROUTER_API_KEY";

    /** OpenAI API key. */
    public static final String OPENAI_API_KEY = "AMSHA_OPENAI_API_KEY";

    /** Google Gemini API key. */
    public static final String GEMINI_API_KEY = "AMSHA_GEMINI_API_KEY";

    /** Cerebras API key. */
    public static final String CEREBRAS_API_KEY = "AMSHA_CEREBRAS_API_KEY";

    /** NVIDIA NIM API key. */
    public static final String NVIDIA_API_KEY = "AMSHA_NVIDIA_API_KEY";

    /** OMLX API key (optional for the local server). */
    public static final String OMLX_API_KEY = "AMSHA_OMLX_API_KEY";

    /** Environment-variable name selecting the active speech synthesis provider. */
    public static final String TTS_PROVIDER = "AMSHA_TTS_PROVIDER";
    /** Environment-variable name enabling optional speech synthesis. */
    public static final String TTS_ENABLED = "AMSHA_TTS_ENABLED";
    /** Stable identifier for the Gnani speech provider. */
    public static final String GNANI_PROVIDER_ID = "gnani";

    /** MicroProfile property name selecting the active speech synthesis provider. */
    public static final String TTS_PROVIDER_PROPERTY = "amsha.tts.provider";

    /** Compatibility default for speech synthesis provider selection. */
    public static final String DEFAULT_TTS_PROVIDER = "omlx";

    /** MicroProfile property shared by optional speech synthesis adapters. */
    public static final String TTS_ENABLED_PROPERTY = "amsha.tts.enabled";

    /** Shared STT timeout property for governed transcription adapters. */
    public static final String STT_TIMEOUT_PROPERTY = "amsha.stt.timeout";

    /** Shared STT payload byte limit for governed transcription adapters. */
    public static final String STT_MAX_BYTES_PROPERTY = "amsha.stt.max-bytes";

    /** Shared STT transcript character limit for governed transcription adapters. */
    public static final String STT_MAX_TRANSCRIPT_CHARS_PROPERTY = "amsha.stt.max-transcript-chars";

    /** Shared path property for local audio normalization tools. */
    public static final String STT_FFMPEG_PATH_PROPERTY = "amsha.stt.ffmpeg-path";

    /** Shared TTS timeout property for synthesis adapters. */
    public static final String TTS_TIMEOUT_PROPERTY = "amsha.tts.timeout";

    /** Shared TTS text character limit for synthesis adapters. */
    public static final String TTS_MAX_INPUT_CHARS_PROPERTY = "amsha.tts.max-input-chars";

    /** Shared TTS audio response byte limit for synthesis adapters. */
    public static final String TTS_MAX_BYTES_PROPERTY = "amsha.tts.max-bytes";

    /** Shared concurrent TTS request limit for synthesis adapters. */
    public static final String TTS_MAX_CONCURRENT_PROPERTY = "amsha.tts.max-concurrent";

    /** Gnani speech API key used by the X-API-Key-ID request header. */
    public static final String GNANI_API_KEY = "AMSHA_GNANI_API_KEY";
    /** Environment-variable name enabling optional Gnani transcription. */
    public static final String GNANI_STT_ENABLED = "AMSHA_STT_GNANI_ENABLED";

    /** MicroProfile property mapped to the optional Gnani speech API key. */
    public static final String GNANI_API_KEY_PROPERTY = "amsha.gnani.api-key";

    /** Property enabling Gnani as an explicit cloud-tier transcription provider. */
    public static final String GNANI_STT_ENABLED_PROPERTY = "amsha.stt.gnani.enabled";

    /** Property supplying the required default Gnani STT language code. */
    public static final String GNANI_STT_DEFAULT_LANGUAGE_PROPERTY = "amsha.stt.gnani.default-language";

    /** Property bounding Gnani STT clip duration before cloud egress. */
    public static final String GNANI_STT_MAX_DURATION_PROPERTY = "amsha.stt.gnani.max-duration";

    /** Property selecting the Gnani Timbre model. */
    public static final String GNANI_TTS_MODEL_PROPERTY = "amsha.tts.gnani.model";

    /** Property selecting the configured Gnani TTS fallback voice. */
    public static final String GNANI_TTS_VOICE_PROPERTY = "amsha.tts.gnani.voice";

    /** Sarvam AI API key. */
    public static final String SARVAM_API_KEY = "AMSHA_SARVAM_API_KEY";

    /** OpenCode API key. */
    public static final String OPENCODE_API_KEY = "AMSHA_OPENCODE_API_KEY";

    /** Hugging Face access token. */
    public static final String HUGGINGFACE_API_KEY = "AMSHA_HUGGINGFACE_API_KEY";

    /** Mistral API key. */
    public static final String MISTRAL_API_KEY = "AMSHA_MISTRAL_API_KEY";

    /** Generic OpenAI-compatible API key. */
    public static final String GENERIC_OPENAI_API_KEY = "AMSHA_GENERIC_OPENAI_API_KEY";

    /** Codex API key. */
    public static final String CODEX_API_KEY = "AMSHA_CODEX_API_KEY";

    // -- Infinite-loop provider API keys ---------------------------------
    // The compaction and classifier slots use separate credentials from the primary LLM slot.

    public static final String COMPACTION_OPENROUTER_API_KEY = "AMSHA_COMPACTION_OPENROUTER_API_KEY";
    public static final String COMPACTION_OPENAI_API_KEY = "AMSHA_COMPACTION_OPENAI_API_KEY";
    public static final String COMPACTION_GEMINI_API_KEY = "AMSHA_COMPACTION_GEMINI_API_KEY";
    public static final String COMPACTION_CEREBRAS_API_KEY = "AMSHA_COMPACTION_CEREBRAS_API_KEY";
    public static final String COMPACTION_NVIDIA_API_KEY = "AMSHA_COMPACTION_NVIDIA_API_KEY";
    public static final String COMPACTION_SARVAM_API_KEY = "AMSHA_COMPACTION_SARVAM_API_KEY";
    public static final String COMPACTION_OPENCODE_API_KEY = "AMSHA_COMPACTION_OPENCODE_API_KEY";
    public static final String COMPACTION_HUGGINGFACE_API_KEY = "AMSHA_COMPACTION_HUGGINGFACE_API_KEY";
    public static final String COMPACTION_MISTRAL_API_KEY = "AMSHA_COMPACTION_MISTRAL_API_KEY";
    public static final String COMPACTION_CLOUDFLARE_API_KEY = "AMSHA_COMPACTION_CLOUDFLARE_API_KEY";
    public static final String COMPACTION_COHERE_API_KEY = "AMSHA_COMPACTION_COHERE_API_KEY";
    public static final String COMPACTION_GENERIC_OPENAI_API_KEY = "AMSHA_COMPACTION_GENERIC_OPENAI_API_KEY";
    public static final String COMPACTION_GROQ_API_KEY = "AMSHA_COMPACTION_GROQ_API_KEY";

    public static final String CLASSIFIER_OPENROUTER_API_KEY = "AMSHA_CLASSIFIER_OPENROUTER_API_KEY";
    public static final String CLASSIFIER_OPENAI_API_KEY = "AMSHA_CLASSIFIER_OPENAI_API_KEY";
    public static final String CLASSIFIER_GEMINI_API_KEY = "AMSHA_CLASSIFIER_GEMINI_API_KEY";
    public static final String CLASSIFIER_CEREBRAS_API_KEY = "AMSHA_CLASSIFIER_CEREBRAS_API_KEY";
    public static final String CLASSIFIER_NVIDIA_API_KEY = "AMSHA_CLASSIFIER_NVIDIA_API_KEY";
    public static final String CLASSIFIER_SARVAM_API_KEY = "AMSHA_CLASSIFIER_SARVAM_API_KEY";
    public static final String CLASSIFIER_OPENCODE_API_KEY = "AMSHA_CLASSIFIER_OPENCODE_API_KEY";
    public static final String CLASSIFIER_HUGGINGFACE_API_KEY = "AMSHA_CLASSIFIER_HUGGINGFACE_API_KEY";
    public static final String CLASSIFIER_MISTRAL_API_KEY = "AMSHA_CLASSIFIER_MISTRAL_API_KEY";
    public static final String CLASSIFIER_CLOUDFLARE_API_KEY = "AMSHA_CLASSIFIER_CLOUDFLARE_API_KEY";
    public static final String CLASSIFIER_COHERE_API_KEY = "AMSHA_CLASSIFIER_COHERE_API_KEY";
    public static final String CLASSIFIER_GENERIC_OPENAI_API_KEY = "AMSHA_CLASSIFIER_GENERIC_OPENAI_API_KEY";
    public static final String CLASSIFIER_GROQ_API_KEY = "AMSHA_CLASSIFIER_GROQ_API_KEY";

    // -- Embedding and decision-plane credentials ------------------------

    public static final String EMBEDDING_OPENAI_API_KEY = "AMSHA_EMBEDDING_OPENAI_API_KEY";
    public static final String EMBEDDING_JINA_API_KEY = "AMSHA_EMBEDDING_JINA_API_KEY";
    public static final String DECISION_TYPESAFE_API_KEY = "AMSHA_DECISION_TYPESAFE_API_KEY";
    public static final String DECISION_CLM_API_KEY = "AMSHA_DECISION_CLM_API_KEY";
    public static final String DECISION_LAYA_API_KEY = "AMSHA_DECISION_LAYA_API_KEY";
    public static final String DECISION_KEV_API_KEY = "AMSHA_DECISION_KEV_API_KEY";
    public static final String DECISION_OMLX_API_KEY = "AMSHA_DECISION_OMLX_API_KEY";

    /** Telegram bot token configured through BotFather. */
    public static final String TELEGRAM_BOT_TOKEN = "TELEGRAM_BOT_TOKEN";

    // -- Primary provider model / endpoint keys ---------------------------
    // Each provider factory reads "<P>_MODEL" and "<P>_BASE_URL" (the value is both the environment
    // variable and the system-property spelling), and the onboarding provenance table names the same
    // two keys. A drifted copy makes the factory fall back to its default model/endpoint, or makes the
    // provenance report a variable the runtime never reads.

    /** Groq model id and API base URL. */
    public static final String GROQ_MODEL = "AMSHA_GROQ_MODEL";

    public static final String GROQ_BASE_URL = "AMSHA_GROQ_BASE_URL";

    /** OpenAI model id and API base URL. */
    public static final String OPENAI_MODEL = "AMSHA_OPENAI_MODEL";

    public static final String OPENAI_BASE_URL = "AMSHA_OPENAI_BASE_URL";

    /** Google Gemini model id and API base URL. */
    public static final String GEMINI_MODEL = "AMSHA_GEMINI_MODEL";

    public static final String GEMINI_BASE_URL = "AMSHA_GEMINI_BASE_URL";

    /** Cerebras model id and API base URL. */
    public static final String CEREBRAS_MODEL = "AMSHA_CEREBRAS_MODEL";

    public static final String CEREBRAS_BASE_URL = "AMSHA_CEREBRAS_BASE_URL";

    /** NVIDIA NIM model id and API base URL. */
    public static final String NVIDIA_MODEL = "AMSHA_NVIDIA_MODEL";

    public static final String NVIDIA_BASE_URL = "AMSHA_NVIDIA_BASE_URL";

    /** OMLX (local MLX server) model id and API base URL. */
    public static final String OMLX_MODEL = "AMSHA_OMLX_MODEL";

    public static final String OMLX_BASE_URL = "AMSHA_OMLX_BASE_URL";

    /** OpenRouter model id and API base URL. */
    public static final String OPENROUTER_MODEL = "AMSHA_OPENROUTER_MODEL";

    public static final String OPENROUTER_BASE_URL = "AMSHA_OPENROUTER_BASE_URL";

    /** Llama.cpp (local server) model id and API base URL. */
    public static final String LLAMACPP_MODEL = "AMSHA_LLAMACPP_MODEL";

    public static final String LLAMACPP_BASE_URL = "AMSHA_LLAMACPP_BASE_URL";

    /** Generic OpenAI-compatible model id and API base URL (both required). */
    public static final String GENERIC_OPENAI_MODEL = "AMSHA_GENERIC_OPENAI_MODEL";

    public static final String GENERIC_OPENAI_BASE_URL = "AMSHA_GENERIC_OPENAI_BASE_URL";

    /** Cloudflare Workers AI model id and API base URL. */
    public static final String CLOUDFLARE_MODEL = "AMSHA_CLOUDFLARE_MODEL";

    public static final String CLOUDFLARE_BASE_URL = "AMSHA_CLOUDFLARE_BASE_URL";

    /** Claude Code model id and API base URL. */
    public static final String CLAUDE_CODE_MODEL = "AMSHA_CLAUDE_CODE_MODEL";

    public static final String CLAUDE_CODE_BASE_URL = "AMSHA_CLAUDE_CODE_BASE_URL";

    /** Sarvam model id and API base URL. */
    public static final String SARVAM_MODEL = "AMSHA_SARVAM_MODEL";

    public static final String SARVAM_BASE_URL = "AMSHA_SARVAM_BASE_URL";

    /** OpenCode model id and API base URL. */
    public static final String OPENCODE_MODEL = "AMSHA_OPENCODE_MODEL";

    public static final String OPENCODE_BASE_URL = "AMSHA_OPENCODE_BASE_URL";

    /** Hugging Face model id and API base URL. */
    public static final String HUGGINGFACE_MODEL = "AMSHA_HUGGINGFACE_MODEL";

    public static final String HUGGINGFACE_BASE_URL = "AMSHA_HUGGINGFACE_BASE_URL";

    /** Mistral model id and API base URL. */
    public static final String MISTRAL_MODEL = "AMSHA_MISTRAL_MODEL";

    public static final String MISTRAL_BASE_URL = "AMSHA_MISTRAL_BASE_URL";

    /** Cohere model id and API base URL. */
    public static final String COHERE_MODEL = "AMSHA_COHERE_MODEL";

    public static final String COHERE_BASE_URL = "AMSHA_COHERE_BASE_URL";

    /** Codex model id and API base URL. */
    public static final String CODEX_MODEL = "AMSHA_CODEX_MODEL";

    public static final String CODEX_BASE_URL = "AMSHA_CODEX_BASE_URL";

    /** Antigravity (agy) model id and API base URL. */
    public static final String AGY_MODEL = "AMSHA_AGY_MODEL";

    public static final String AGY_BASE_URL = "AMSHA_AGY_BASE_URL";

    /** Kimchi model id and API base URL. */
    public static final String KIMCHI_MODEL = "AMSHA_KIMCHI_MODEL";

    public static final String KIMCHI_BASE_URL = "AMSHA_KIMCHI_BASE_URL";

    /** Foundry model id and API base URL. */
    public static final String FOUNDRY_MODEL = "AMSHA_FOUNDRY_MODEL";

    public static final String FOUNDRY_BASE_URL = "AMSHA_FOUNDRY_BASE_URL";

    // -- Infinite-loop provider keys --------------------------------------
    // The failover provider lists and their cooldown/latency knobs. The three cooldown keys are read
    // by four provider classes (agent, compaction and intent in core; embedding in rag), so a drifted
    // copy silently gives one loop a different cooldown than the others.

    /** Comma-separated failover providers for the agent chat loop. */
    public static final String INFINITE_LOOP_LLM_PROVIDERS = "amsha.infinite-loop.llm.providers";

    /** Comma-separated failover providers for the compaction loop. */
    public static final String INFINITE_LOOP_COMPACTION_PROVIDERS = "amsha.infinite-loop.compaction.providers";

    /** Comma-separated failover providers for the intent-classifier loop. */
    public static final String INFINITE_LOOP_CLASSIFIER_PROVIDERS = "amsha.infinite-loop.classifier.providers";

    /** Comma-separated failover providers for the embedding loop. */
    public static final String INFINITE_LOOP_EMBEDDING_PROVIDERS = "amsha.infinite-loop.embedding.providers";

    /** Base cooldown (seconds) after a slot failure, shared by every infinite-loop provider. */
    public static final String INFINITE_LOOP_COOLDOWN_BASE_SECONDS = "amsha.infinite-loop.cooldown-base-seconds";

    /** Maximum cooldown (seconds) after repeated slot failures. */
    public static final String INFINITE_LOOP_COOLDOWN_MAX_SECONDS = "amsha.infinite-loop.cooldown-max-seconds";

    /** Cooldown (seconds) applied to a transient (retryable) failure. */
    public static final String INFINITE_LOOP_COOLDOWN_TRANSIENT_SECONDS =
            "amsha.infinite-loop.cooldown-transient-seconds";

    /** Maximum acceptable per-slot latency (milliseconds) before a slot is considered slow. */
    public static final String INFINITE_LOOP_MAX_LATENCY_MS = "amsha.infinite-loop.max-latency-ms";

    // -- Infinite-loop provider keys: environment spellings ---------------
    // The setup wizard and CLI utilities write these env names into .env; MicroProfile maps each onto the
    // matching "amsha.infinite-loop.*" property above (a different literal), and onboarding's provenance
    // reads the same env names. Named with the full literal to stay distinct from the property form.

    /** Env spelling of {@link #INFINITE_LOOP_LLM_PROVIDERS}. */
    public static final String AMSHA_INFINITE_LOOP_LLM_PROVIDERS = "AMSHA_INFINITE_LOOP_LLM_PROVIDERS";

    /** Env spelling of {@link #INFINITE_LOOP_COMPACTION_PROVIDERS}. */
    public static final String AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS = "AMSHA_INFINITE_LOOP_COMPACTION_PROVIDERS";

    /** Env spelling of {@link #INFINITE_LOOP_CLASSIFIER_PROVIDERS}. */
    public static final String AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS = "AMSHA_INFINITE_LOOP_CLASSIFIER_PROVIDERS";

    /** Env spelling of {@link #INFINITE_LOOP_EMBEDDING_PROVIDERS}. */
    public static final String AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS = "AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS";

    // -- Local model / runtime paths --------------------------------------

    /** Base data directory (models, SQLite database, workspaces). */
    public static final String DATA_DIR = "AMSHA_DATA_DIR";

    /** Root directory for local ONNX models; outranked by the {@code amsha.onnx.model-root} property. */
    public static final String ONNX_MODEL_ROOT = "AMSHA_ONNX_MODEL_ROOT";

    /** Whether local ONNX inference is enabled. */
    public static final String ONNX_ENABLED = "AMSHA_ONNX_ENABLED";

    /** Path to the ffmpeg binary used by local speech-to-text. */
    public static final String STT_FFMPEG_PATH = "AMSHA_STT_FFMPEG_PATH";
}
