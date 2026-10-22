package com.amsha.app.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.amsha.app.cli.CliArguments;
import com.amsha.app.cli.ModelInstaller;
import com.amsha.common.config.ConfigKeys;
import com.amsha.llm.api.ModelDefaults;
import com.amsha.localml.onnx.LocalAudioTranscoder;
import com.amsha.localml.onnx.OnnxEmbeddingProviderFactory;
import com.amsha.localml.onnx.OnnxModelBundleIds;

/**
 * Validates local ONNX model readiness during CLI bootstrap and guides the user
 * with actionable commands when required models are missing.
 */
@SuppressWarnings("java:S106")
public final class LocalModelValidator {

    private static final String ONNX_ENABLED_PROPERTY = "amsha.onnx.enabled";
    private static final String LOCAL_STT_ENABLED_PROPERTY = "amsha.stt.local.enabled";
    public static final String LOCAL_STT_ENABLED_ENV = "AMSHA_STT_LOCAL_ENABLED";
    private static final String RERANKER_ENABLED_PROPERTY = "amsha.rag.reranker.enabled";
    public static final String RERANKER_ENABLED_ENV = "AMSHA_RAG_RERANKER_ENABLED";
    private static final String RERANKER_BUNDLE_ID_PROPERTY = "amsha.rag.reranker.bundle-id";
    private static final String RERANKER_BUNDLE_ID_ENV = "AMSHA_RAG_RERANKER_BUNDLE_ID";
    private static final String BOOLEAN_FALSE = Boolean.FALSE.toString();
    private static final String ONNX_EMBEDDING_MODEL_PROPERTY = "amsha.embedding.onnx.model";
    private static final String ONNX_EMBEDDING_MODEL_ENV = "AMSHA_EMBEDDING_ONNX_MODEL";
    private static final String EMBEDDING_PROVIDERS_PROPERTY = ConfigKeys.INFINITE_LOOP_EMBEDDING_PROVIDERS;
    private static final String EMBEDDING_PROVIDERS_ENV = ConfigKeys.AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS;
    private static final String ONNX_PROVIDER_ID = OnnxEmbeddingProviderFactory.PROVIDER_ID;
    private static final String EMBEDDING_PROVIDER_JINA = "jina";
    private static final String OS_MACOS_FRAGMENT = "mac";
    private static final String OS_WINDOWS_FRAGMENT = "win";

    private LocalModelValidator() {}

    public static void validateLocalModels(Path binaryDir, String[] args) {
        if (args != null && args.length > 0 && CliArguments.isInformational(args)) {
            return;
        }

        Path modelRoot = ModelInstaller.resolveModelRoot();
        StartupPreflightException localModelFailure = null;
        if (isLocalSttEnabled()) {
            if (!isOnnxEnabled()) {
                localModelFailure = new StartupPreflightException(
                        "Local voice transcription requires " + ConfigKeys.ONNX_ENABLED + "=true. Set "
                                + ConfigKeys.ONNX_ENABLED + "=true or disable local transcription with "
                                + LOCAL_STT_ENABLED_ENV + "=false.");
            } else {
                ModelInstaller.ModelCatalogEntry whisper = ModelInstaller.findModel(ModelInstaller.SELECTOR_WHISPER)
                        .orElseThrow(
                                () -> new IllegalStateException("Whisper model is missing from the model catalog."));
                Path whisperDir = modelRoot.resolve(ModelInstaller.WHISPER_BUNDLE_ID);
                if (ModelInstaller.checkStatus(whisper, modelRoot) != ModelInstaller.ModelStatus.INSTALLED) {
                    printMissingWhisperBanner(whisperDir);
                    localModelFailure = new StartupPreflightException(
                            "Whisper model bundle is missing or incomplete at " + whisperDir + ". Install it with "
                                    + ModelInstaller.launcherCommand() + " models install "
                                    + ModelInstaller.SELECTOR_WHISPER + " or disable local transcription with "
                                    + LOCAL_STT_ENABLED_ENV + "=false.");
                }

                String ffmpegPath = System.getProperty(
                        ConfigKeys.STT_FFMPEG_PATH_PROPERTY, System.getenv(ConfigKeys.STT_FFMPEG_PATH));
                if (!LocalAudioTranscoder.isFfmpegAvailable(ffmpegPath)) {
                    printMissingFfmpegBanner(ffmpegPath != null && !ffmpegPath.isBlank() ? ffmpegPath : "ffmpeg");
                }
            }
        }

        validateEmbeddingProvider(modelRoot);
        warnIfRerankerUnavailable(modelRoot);
        if (localModelFailure != null) {
            throw localModelFailure;
        }
    }

    public static boolean isLocalSttEnabled() {
        String value = System.getProperty(LOCAL_STT_ENABLED_PROPERTY, System.getenv(LOCAL_STT_ENABLED_ENV));
        return Boolean.parseBoolean(value);
    }

    public static boolean isOnnxEnabled() {
        return Boolean.parseBoolean(configurationValue(ONNX_ENABLED_PROPERTY, ConfigKeys.ONNX_ENABLED, BOOLEAN_FALSE));
    }

    public static boolean isRerankerEnabled() {
        return Boolean.parseBoolean(configurationValue(RERANKER_ENABLED_PROPERTY, RERANKER_ENABLED_ENV, BOOLEAN_FALSE));
    }

    /**
     * Returns whether an installed local ONNX feature needs its native runtime initialized.
     * Missing optional embedding or reranker bundles leave the configured fallback path usable.
     */
    public static boolean shouldInitializeOnnxRuntime() {
        if (isLocalSttEnabled()) {
            return true;
        }

        if (!isOnnxEnabled()) {
            return false;
        }

        Path modelRoot = ModelInstaller.resolveModelRoot();
        if (configuredEmbeddingProviders().contains(ONNX_PROVIDER_ID) && onnxProviderIssue(modelRoot) == null) {
            return true;
        }

        if (!isRerankerEnabled()) {
            return false;
        }

        String bundleId = configurationValue(
                RERANKER_BUNDLE_ID_PROPERTY, RERANKER_BUNDLE_ID_ENV, ModelInstaller.RERANKER_BUNDLE_ID);
        return isModelInstalled(bundleId, modelRoot);
    }

    private static void warnIfRerankerUnavailable(Path modelRoot) {
        if (!isRerankerEnabled()) {
            return;
        }

        String bundleId = configurationValue(
                RERANKER_BUNDLE_ID_PROPERTY, RERANKER_BUNDLE_ID_ENV, ModelInstaller.RERANKER_BUNDLE_ID);
        boolean onnxEnabled = isOnnxEnabled();
        Optional<ModelInstaller.ModelCatalogEntry> entry = ModelInstaller.findModel(bundleId);
        boolean modelInstalled = isModelInstalled(bundleId, modelRoot);
        if (onnxEnabled && modelInstalled) {
            return;
        }

        System.err.println("[Amsha Agent] Optional reranking is unavailable; continuing with hybrid search.");
        if (onnxEnabled) {
            System.err.printf("The model bundle %s is missing or incomplete.%n", bundleId);
            System.err.printf(
                    "Install with: %s models install %s%n",
                    ModelInstaller.launcherCommand(),
                    entry.map(catalogEntry ->
                                    catalogEntry.aliases().stream().findFirst().orElse(catalogEntry.id()))
                            .orElse(bundleId));
        } else {
            System.err.printf("Enable ONNX with %s=true to use reranking.%n", ConfigKeys.ONNX_ENABLED);
        }
        System.err.printf("To disable reranking, set %s=false.%n", RERANKER_ENABLED_ENV);
    }

    private static boolean isModelInstalled(String bundleId, Path modelRoot) {
        Optional<ModelInstaller.ModelCatalogEntry> entry = ModelInstaller.findModel(bundleId);
        return entry.map(catalogEntry ->
                        ModelInstaller.checkStatus(catalogEntry, modelRoot) == ModelInstaller.ModelStatus.INSTALLED)
                .orElseGet(() ->
                        Files.isRegularFile(modelRoot.resolve(bundleId).resolve(ModelInstaller.MANIFEST_FILE_NAME)));
    }

    private static void validateEmbeddingProvider(Path modelRoot) {
        List<String> providers = configuredEmbeddingProviders();
        if (providers.isEmpty()) {
            throw new StartupPreflightException("No embedding provider is configured. Set " + EMBEDDING_PROVIDERS_ENV
                    + " to a configured provider such as ollama, jina, openai, or onnx.");
        }
        if (!providers.contains(ONNX_PROVIDER_ID)) {
            return;
        }

        String issue = onnxProviderIssue(modelRoot);
        if (issue == null) {
            return;
        }

        List<String> fallbacks = providers.stream()
                .filter(provider -> !ONNX_PROVIDER_ID.equals(provider))
                .toList();
        if (fallbacks.isEmpty()) {
            throw new StartupPreflightException(formatOnnxProviderIssue(issue, modelRoot));
        }

        System.err.printf(
                "[Amsha Agent] ONNX embedding slot unavailable: %s%nContinuing with configured provider(s): %s.%n",
                issue, String.join(", ", fallbacks));
    }

    private static List<String> configuredEmbeddingProviders() {
        String configured = configurationValue(
                EMBEDDING_PROVIDERS_PROPERTY, EMBEDDING_PROVIDERS_ENV, ModelDefaults.PROVIDER_OLLAMA);
        return Arrays.stream(configured.split(",", -1))
                .map(String::trim)
                .map(provider -> provider.toLowerCase(Locale.ROOT))
                .filter(provider -> !provider.isEmpty())
                .toList();
    }

    private static String onnxProviderIssue(Path modelRoot) {
        if (!isOnnxEnabled()) {
            return "onnx is selected but " + ConfigKeys.ONNX_ENABLED + " is not true";
        }

        String modelId = configurationValue(ONNX_EMBEDDING_MODEL_PROPERTY, ONNX_EMBEDDING_MODEL_ENV, "");
        if (modelId.isBlank()) {
            return ONNX_EMBEDDING_MODEL_ENV + " is blank";
        }
        if (!OnnxModelBundleIds.isValid(modelId)) {
            return "ONNX model bundle ID contains unsupported characters: " + modelId;
        }

        Optional<ModelInstaller.ModelCatalogEntry> catalogEntry = ModelInstaller.findModel(modelId);
        if (catalogEntry.isPresent()) {
            ModelInstaller.ModelCatalogEntry entry = catalogEntry.get();
            if (!entry.id().equals(modelId)) {
                return ONNX_EMBEDDING_MODEL_ENV + " must use canonical bundle ID '" + entry.id()
                        + "' rather than alias '" + modelId + "'";
            }
            if (ModelInstaller.checkStatus(entry, modelRoot) != ModelInstaller.ModelStatus.INSTALLED) {
                return "bundle '" + modelId + "' is missing or incomplete at " + modelRoot.resolve(modelId);
            }
            return null;
        }

        Path manifest = modelRoot.resolve(modelId).resolve(ModelInstaller.MANIFEST_FILE_NAME);
        return Files.isRegularFile(manifest)
                ? null
                : "bundle '" + modelId + "' is missing at " + modelRoot.resolve(modelId);
    }

    private static String configurationValue(String propertyName, String envName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value != null) {
            return value;
        }
        value = System.getProperty(envName);
        if (value != null) {
            return value;
        }
        value = System.getenv(envName);
        return value == null ? defaultValue : value;
    }

    private static String formatOnnxProviderIssue(String issue, Path modelRoot) {
        StringBuilder message = new StringBuilder("Amsha startup preflight failed: ")
                .append(issue)
                .append(".\nConfigured embedding provider: ")
                .append(ONNX_PROVIDER_ID)
                .append('.')
                .append("\nModel directory: ")
                .append(modelRoot);
        String modelId = configurationValue(ONNX_EMBEDDING_MODEL_PROPERTY, ONNX_EMBEDDING_MODEL_ENV, "");
        Optional<ModelInstaller.ModelCatalogEntry> entry = ModelInstaller.findModel(modelId);
        if (entry.isPresent()) {
            String installName = entry.get().aliases().stream()
                    .findFirst()
                    .orElse(entry.get().id());
            String launcher = ModelInstaller.launcherCommand();
            message.append("\nInstall with: ")
                    .append(launcher)
                    .append(" models install ")
                    .append(installName);
            message.append("\nVerify with: ")
                    .append(launcher)
                    .append(" models verify ")
                    .append(installName);
        } else {
            message.append("\nList supported bundles with: ")
                    .append(ModelInstaller.launcherCommand())
                    .append(" models list");
        }
        message.append("\nTo avoid ONNX, configure another embedding provider in ")
                .append(EMBEDDING_PROVIDERS_ENV)
                .append(" (for example ")
                .append(ModelDefaults.PROVIDER_OLLAMA)
                .append(", ")
                .append(EMBEDDING_PROVIDER_JINA)
                .append(", ")
                .append(ModelDefaults.PROVIDER_OPENAI)
                .append(").");
        return message.toString();
    }

    public static final class StartupPreflightException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public StartupPreflightException(String message) {
            super(message);
        }
    }

    private static void printMissingWhisperBanner(Path targetDir) {
        System.err.println();
        System.err.println("  ┌──────────────────────────────────────────────────────────────────────────┐");
        System.err.printf("  │ %-70s │%n", "🎙️  Local Whisper Model Bundle Missing or Incomplete");
        System.err.println("  ├──────────────────────────────────────────────────────────────────────────┤");
        System.err.printf("  │ %-70s │%n", "Local transcription requires " + LOCAL_STT_ENABLED_ENV + "=true.");
        System.err.printf(
                "  │ %-70s │%n", "Bundle " + ModelInstaller.WHISPER_BUNDLE_ID + " is missing or incomplete at:");
        System.err.printf("  │   %-70s │%n", targetDir.toString());
        System.err.println("  │                                                                          │");
        System.err.println("  │ Install using this binary:                                               │");
        System.err.printf(
                "  │   %-70s │%n",
                ModelInstaller.launcherCommand() + " models install " + ModelInstaller.SELECTOR_WHISPER);
        System.err.println("  │                                                                          │");
        System.err.println("  │ Or disable local transcription in .env:                                  │");
        System.err.printf("  │   %-70s │%n", LOCAL_STT_ENABLED_ENV + "=false");
        System.err.println("  └──────────────────────────────────────────────────────────────────────────┘");
        System.err.println();
    }

    private static void printMissingFfmpegBanner(String configuredPath) {
        System.err.println();
        System.err.println("  ┌──────────────────────────────────────────────────────────────────────────┐");
        System.err.println("  │ ⚠️  ffmpeg Executable Not Found or Not Runnable                         │");
        System.err.println("  ├──────────────────────────────────────────────────────────────────────────┤");
        System.err.printf("  │ %-70s │%n", "Local transcription requires " + LOCAL_STT_ENABLED_ENV + "=true.");
        System.err.println("  │ but 'ffmpeg' was not found or failed execution at:                       │");
        System.err.printf("  │   %-70s │%n", configuredPath);
        System.err.println("  │                                                                          │");
        System.err.println("  │ ffmpeg is required to decode voice notes (OGG/Opus, WebM, MP3) to WAV.   │");
        System.err.println("  │                                                                          │");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains(OS_MACOS_FRAGMENT)) {
            System.err.println("  │ Install ffmpeg on macOS using Homebrew:                                  │");
            System.err.println("  │   brew install ffmpeg                                                    │");
        } else if (os.contains(OS_WINDOWS_FRAGMENT)) {
            System.err.println("  │ Install ffmpeg on Windows using winget or Chocolatey:                    │");
            System.err.println("  │   winget install Gyan.FFmpeg                                             │");
            System.err.println("  │   choco install ffmpeg                                                   │");
        } else {
            System.err.println("  │ Install ffmpeg on Linux using your package manager:                      │");
            System.err.println("  │   sudo apt update && sudo apt install -y ffmpeg   (Ubuntu/Debian)        │");
            System.err.println("  │   sudo dnf install ffmpeg                         (Fedora/RHEL)          │");
        }
        System.err.println("  │                                                                          │");
        System.err.println("  │ If installed in a custom location, configure in your .env:               │");
        System.err.printf("  │ %-70s │%n", ConfigKeys.STT_FFMPEG_PATH + "=/path/to/ffmpeg");
        System.err.println("  └──────────────────────────────────────────────────────────────────────────┘");
        System.err.println();
    }
}
