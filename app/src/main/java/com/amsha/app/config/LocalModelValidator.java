package com.amsha.app.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.amsha.app.cli.ModelInstaller;
import com.amsha.common.config.ConfigKeys;
import com.amsha.localml.onnx.LocalAudioTranscoder;

/**
 * Validates local ONNX model readiness during CLI bootstrap and guides the user
 * with actionable commands when required models are missing.
 */
@SuppressWarnings("java:S106")
public final class LocalModelValidator {

    private LocalModelValidator() {}

    public static void validateLocalModels(Path binaryDir, String[] args) {
        if (args != null && args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("models".equals(sub) || "--help".equals(sub) || "-h".equals(sub) || "--version".equals(sub)) {
                return;
            }
        }

        Path modelRoot = ModelInstaller.resolveModelRoot();

        // Check Local Whisper STT
        if (isLocalSttEnabled()) {
            Path whisperDir = modelRoot.resolve("whisper-tiny-en-v1");
            Path manifest = whisperDir.resolve("manifest.json");
            if (!Files.exists(manifest)) {
                printMissingWhisperBanner(whisperDir);
            }
            String ffmpegPath =
                    System.getProperty(ConfigKeys.STT_FFMPEG_PATH_PROPERTY, System.getenv(ConfigKeys.STT_FFMPEG_PATH));
            if (!LocalAudioTranscoder.isFfmpegAvailable(ffmpegPath)) {
                printMissingFfmpegBanner(ffmpegPath != null && !ffmpegPath.isBlank() ? ffmpegPath : "ffmpeg");
            }
        }

        // Check Local ONNX Embedding
        if (isLocalEmbeddingEnabled()) {
            Path mpnetDir = modelRoot.resolve("all-mpnet-base-v2-e8c3b32");
            Path manifest = mpnetDir.resolve("manifest.json");
            if (!Files.exists(manifest)) {
                printMissingEmbeddingBanner(mpnetDir);
            }
        }
    }

    private static boolean isLocalSttEnabled() {
        String val = System.getProperty("amsha.stt.local.enabled", System.getenv("AMSHA_STT_LOCAL_ENABLED"));
        return "true".equalsIgnoreCase(val);
    }

    private static boolean isLocalEmbeddingEnabled() {
        String onnxEnabled = System.getProperty("amsha.onnx.enabled", System.getenv(ConfigKeys.ONNX_ENABLED));
        String embeddingModel =
                System.getProperty("amsha.embedding.onnx.model", System.getenv("AMSHA_EMBEDDING_ONNX_MODEL"));
        return "true".equalsIgnoreCase(onnxEnabled) && embeddingModel != null && !embeddingModel.isBlank();
    }

    private static void printMissingWhisperBanner(Path targetDir) {
        System.err.println();
        System.err.println("  ┌──────────────────────────────────────────────────────────────────────────┐");
        System.err.println("  │ 🎙️  Local Whisper Model Bundle Not Found                                │");
        System.err.println("  ├──────────────────────────────────────────────────────────────────────────┤");
        System.err.println("  │ Local audio transcription is enabled (AMSHA_STT_LOCAL_ENABLED=true),     │");
        System.err.println("  │ but the required model bundle 'whisper-tiny-en-v1' was not found in:     │");
        System.err.printf("  │   %-70s │%n", targetDir.toString());
        System.err.println("  │                                                                          │");
        System.err.println("  │ To install it automatically using this binary, run:                      │");
        System.err.println("  │                                                                          │");
        System.err.println("  │   ./amsha models install whisper                                         │");
        System.err.println("  │                                                                          │");
        System.err.println("  │ Or disable local transcription in your .env:                             │");
        System.err.println("  │   AMSHA_STT_LOCAL_ENABLED=false                                          │");
        System.err.println("  └──────────────────────────────────────────────────────────────────────────┘");
        System.err.println();
    }

    private static void printMissingEmbeddingBanner(Path targetDir) {
        System.err.println();
        System.err.println("  ┌──────────────────────────────────────────────────────────────────────────┐");
        System.err.println("  │ 🧠  Local Embedding Model Bundle Not Found                              │");
        System.err.println("  ├──────────────────────────────────────────────────────────────────────────┤");
        System.err.println("  │ Local ONNX embedding is configured, but the model bundle was not found:  │");
        System.err.printf("  │   %-70s │%n", targetDir.toString());
        System.err.println("  │                                                                          │");
        System.err.println("  │ To install it automatically using this binary, run:                      │");
        System.err.println("  │   ./amsha models install mpnet                                           │");
        System.err.println("  └──────────────────────────────────────────────────────────────────────────┘");
        System.err.println();
    }

    private static void printMissingFfmpegBanner(String configuredPath) {
        System.err.println();
        System.err.println("  ┌──────────────────────────────────────────────────────────────────────────┐");
        System.err.println("  │ ⚠️  ffmpeg Executable Not Found or Not Runnable                         │");
        System.err.println("  ├──────────────────────────────────────────────────────────────────────────┤");
        System.err.println("  │ Local audio transcription is enabled (AMSHA_STT_LOCAL_ENABLED=true),     │");
        System.err.println("  │ but 'ffmpeg' was not found or failed execution at:                       │");
        System.err.printf("  │   %-70s │%n", configuredPath);
        System.err.println("  │                                                                          │");
        System.err.println("  │ ffmpeg is required to decode voice notes (OGG/Opus, WebM, MP3) to WAV.   │");
        System.err.println("  │                                                                          │");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            System.err.println("  │ Install ffmpeg on macOS using Homebrew:                                  │");
            System.err.println("  │   brew install ffmpeg                                                    │");
        } else if (os.contains("win")) {
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
        System.err.println("  │   AMSHA_STT_FFMPEG_PATH=/path/to/ffmpeg                                  │");
        System.err.println("  └──────────────────────────────────────────────────────────────────────────┘");
        System.err.println();
    }
}
