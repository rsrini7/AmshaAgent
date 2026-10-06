package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.amsha.localml.onnx.LocalAudioTranscoder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Plain-JUnit tests for {@link LocalModelValidator}.
 *
 * <p>The validator's own inputs are {@code System.getProperty} lookups with a {@code System.getenv} fallback,
 * and the property therefore wins for the STT / ONNX / ffmpeg flags — so the properties set below are the
 * single source of truth for those, and the assertions are deterministic rather than machine-dependent.
 *
 * <p>Environment-backed settings receive explicit system-property values in {@link #captureStderr()} so each
 * test controls only the feature it is exercising. The model-root property is pinned to the
 * temporary directory and takes precedence over {@code AMSHA_ONNX_MODEL_ROOT}.
 *
 * <p>{@code os.name} is likewise a property, which makes the macOS / Windows / Linux branches of the ffmpeg
 * banner all reachable from one host instead of leaving two of them permanently dark.
 */
class LocalModelValidatorTest {

    private static final String[] NO_ARGS = new String[0];

    private static final String STT_ENABLED = "amsha.stt.local.enabled";
    private static final String FFMPEG_PATH = "amsha.stt.ffmpeg-path";
    private static final String ONNX_ENABLED = "amsha.onnx.enabled";
    private static final String EMBEDDING_MODEL = "amsha.embedding.onnx.model";
    private static final String MODEL_ROOT = "amsha.onnx.model-root";
    private static final String OS_NAME = "os.name";

    private static final String MPNET = "all-mpnet-base-v2-e8c3b32";
    private static final String WHISPER = "whisper-tiny-en-v1";

    private static final String WHISPER_BANNER_TITLE = "Local Whisper Model Bundle Not Found";
    private static final String EMBEDDING_BANNER_TITLE = "Local Embedding Model Bundle Not Found";
    private static final String FFMPEG_BANNER_TITLE = "ffmpeg Executable Not Found or Not Runnable";

    @TempDir
    Path modelRoot;

    private PrintStream originalErr;
    private String originalOsName;
    private ByteArrayOutputStream capturedErr;
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void captureStderr() {
        originalErr = System.err;
        originalOsName = System.getProperty(OS_NAME);
        // Snapshot every property this class touches so @AfterEach restores rather than clobbers: a bare
        // clearProperty would erase a value another test class had deliberately installed.
        for (String key : new String[] {MODEL_ROOT, STT_ENABLED, FFMPEG_PATH, ONNX_ENABLED, EMBEDDING_MODEL}) {
            originalProperties.put(key, System.getProperty(key));
        }
        capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));
        // Pin the model root at the temp dir so resolution never depends on the developer's machine.
        System.setProperty(MODEL_ROOT, modelRoot.toString());
        System.setProperty(STT_ENABLED, "false");
        System.setProperty(FFMPEG_PATH, "");
        System.setProperty(ONNX_ENABLED, "false");
        System.setProperty(EMBEDDING_MODEL, "");
    }

    @AfterEach
    void restoreProcessState() {
        System.setErr(originalErr);
        // os.name must be restored, not cleared — clearing it would corrupt the JVM for later tests.
        System.setProperty(OS_NAME, originalOsName);
        originalProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    private String stderr() {
        System.err.flush();
        return capturedErr.toString(StandardCharsets.UTF_8);
    }

    /** Runs the validator with ffmpeg availability stubbed, so no real process is ever spawned. */
    private void validate(String[] args, boolean ffmpegAvailable) {
        try (MockedStatic<LocalAudioTranscoder> ffmpeg = mockStatic(LocalAudioTranscoder.class)) {
            ffmpeg.when(() -> LocalAudioTranscoder.isFfmpegAvailable(any())).thenReturn(ffmpegAvailable);
            LocalModelValidator.validateLocalModels(modelRoot, args);
        }
    }

    private void writeBundleManifest(String bundleDirName) throws Exception {
        Path dir = modelRoot.resolve(bundleDirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("manifest.json"), "{}");
    }

    private void enableStt() {
        System.setProperty(STT_ENABLED, "true");
    }

    private void enableEmbedding() {
        System.setProperty(ONNX_ENABLED, "true");
        System.setProperty(EMBEDDING_MODEL, MPNET);
    }

    @Test
    @DisplayName("known subcommands short-circuit before any model or ffmpeg check runs")
    void knownSubcommandsShortCircuit() {
        // Everything is missing AND both subsystems are enabled, so any output at all would prove the
        // guard did not fire.
        enableStt();
        enableEmbedding();

        for (String sub : new String[] {"models", "--help", "-h", "--version", "MODELS", "--HELP"}) {
            capturedErr.reset();
            validate(new String[] {sub}, false);
            assertEquals("", stderr(), "argument '" + sub + "' must short-circuit the validator");
        }
    }

    @Test
    @DisplayName("an unrelated first argument still runs the checks")
    void unrelatedFirstArgumentStillValidates() {
        enableStt();
        validate(new String[] {"serve"}, false);
        assertTrue(stderr().contains(WHISPER_BANNER_TITLE), stderr());
    }

    @Test
    @DisplayName("null and empty argument arrays still run the checks")
    void nullAndEmptyArgsStillValidate() {
        enableStt();
        validate(null, false);
        assertTrue(stderr().contains(WHISPER_BANNER_TITLE), stderr());

        capturedErr.reset();
        validate(NO_ARGS, false);
        assertTrue(stderr().contains(WHISPER_BANNER_TITLE), stderr());
    }

    @Test
    @DisplayName("whisper banner appears when STT is enabled and the bundle is missing")
    void whisperBannerAppearsWhenBundleMissing() {
        enableStt();
        validate(NO_ARGS, true);

        String out = stderr();
        assertTrue(out.contains(WHISPER_BANNER_TITLE), out);
        assertTrue(out.contains("./amsha models install whisper"), out);
        assertTrue(out.contains(modelRoot.resolve(WHISPER).toString()), out);
        assertFalse(out.contains(FFMPEG_BANNER_TITLE), "ffmpeg is available, so that banner must not print");
    }

    @Test
    @DisplayName("whisper banner is suppressed when the manifest exists")
    void whisperBannerSuppressedWhenManifestPresent() throws Exception {
        enableStt();
        writeBundleManifest(WHISPER);
        validate(NO_ARGS, true);

        assertFalse(stderr().contains(WHISPER_BANNER_TITLE), stderr());
    }

    @Test
    @DisplayName("STT checks are skipped unless the flag is exactly true")
    void sttSkippedWhenFlagIsNotTrue() {
        for (String value : new String[] {"yes", "1", "on", "false", ""}) {
            System.setProperty(STT_ENABLED, value);
            capturedErr.reset();
            validate(NO_ARGS, false);
            assertEquals("", stderr(), "flag value '" + value + "' must not enable the STT checks");
        }
    }

    @Test
    @DisplayName("ffmpeg banner appears when STT is enabled and ffmpeg is not runnable")
    void ffmpegBannerAppearsWhenFfmpegUnavailable() throws Exception {
        enableStt();
        writeBundleManifest(WHISPER); // isolate: no whisper banner should compete for attention
        validate(NO_ARGS, false);

        String out = stderr();
        assertTrue(out.contains(FFMPEG_BANNER_TITLE), out);
        assertFalse(out.contains(WHISPER_BANNER_TITLE), out);
    }

    @Test
    @DisplayName("ffmpeg banner names the configured path when one is set")
    void ffmpegBannerNamesConfiguredPath() {
        enableStt();
        System.setProperty(FFMPEG_PATH, "/custom/bin/ffmpeg");
        validate(NO_ARGS, false);

        assertTrue(stderr().contains(String.format("  │   %-70s │", "/custom/bin/ffmpeg")), stderr());
    }

    @Test
    @DisplayName("ffmpeg banner falls back to the bare command name when the path is blank")
    void ffmpegBannerFallsBackToBareCommandName() {
        enableStt();
        System.setProperty(FFMPEG_PATH, "   ");
        validate(NO_ARGS, false);

        assertTrue(stderr().contains(String.format("  │   %-70s │", "ffmpeg")), stderr());
    }

    @Test
    @DisplayName("the ffmpeg banner prints the macOS installer hint on macOS")
    void ffmpegBannerPrintsMacHint() {
        enableStt();
        System.setProperty(OS_NAME, "Mac OS X");
        validate(NO_ARGS, false);

        assertTrue(stderr().contains("brew install ffmpeg"), stderr());
    }

    @Test
    @DisplayName("the ffmpeg banner prints the Windows installer hint on Windows")
    void ffmpegBannerPrintsWindowsHint() {
        enableStt();
        System.setProperty(OS_NAME, "Windows 11");
        validate(NO_ARGS, false);

        String out = stderr();
        assertTrue(out.contains("winget install Gyan.FFmpeg"), out);
        assertTrue(out.contains("choco install ffmpeg"), out);
        assertFalse(out.contains("brew install ffmpeg"), out);
    }

    @Test
    @DisplayName("the ffmpeg banner prints the Linux installer hint elsewhere")
    void ffmpegBannerPrintsLinuxHint() {
        enableStt();
        System.setProperty(OS_NAME, "Linux");
        validate(NO_ARGS, false);

        String out = stderr();
        assertTrue(out.contains("sudo apt update && sudo apt install -y ffmpeg"), out);
        assertFalse(out.contains("brew install ffmpeg"), out);
    }

    @Test
    @DisplayName("embedding banner appears when ONNX is enabled with a model name and the bundle is missing")
    void embeddingBannerAppearsWhenBundleMissing() {
        enableEmbedding();
        validate(NO_ARGS, true);

        String out = stderr();
        assertTrue(out.contains(EMBEDDING_BANNER_TITLE), out);
        assertTrue(out.contains("./amsha models install mpnet"), out);
        assertTrue(out.contains(modelRoot.resolve(MPNET).toString()), out);
    }

    @Test
    @DisplayName("embedding banner is suppressed when the manifest exists")
    void embeddingBannerSuppressedWhenManifestPresent() throws Exception {
        enableEmbedding();
        writeBundleManifest(MPNET);
        validate(NO_ARGS, true);

        assertFalse(stderr().contains(EMBEDDING_BANNER_TITLE), stderr());
    }

    @Test
    @DisplayName("embedding checks are skipped when the ONNX flag is not true")
    void embeddingSkippedWhenOnnxFlagNotTrue() {
        System.setProperty(ONNX_ENABLED, "false");
        System.setProperty(EMBEDDING_MODEL, MPNET);
        validate(NO_ARGS, true);

        assertFalse(stderr().contains(EMBEDDING_BANNER_TITLE), stderr());
    }

    @Test
    @DisplayName("embedding checks are skipped when the model name is blank")
    void embeddingSkippedWhenModelNameBlank() {
        System.setProperty(ONNX_ENABLED, "true");
        System.setProperty(EMBEDDING_MODEL, "   ");
        validate(NO_ARGS, true);

        assertFalse(stderr().contains(EMBEDDING_BANNER_TITLE), stderr());
    }

    @Test
    @DisplayName("embedding checks are skipped when the model name is absent")
    void embeddingSkippedWhenModelNameAbsent() {
        System.setProperty(ONNX_ENABLED, "true");
        validate(NO_ARGS, true);

        assertFalse(stderr().contains(EMBEDDING_BANNER_TITLE), stderr());
    }

    @Test
    @DisplayName("both banners can appear together when both subsystems are enabled")
    void bothBannersAppearTogether() {
        enableStt();
        enableEmbedding();
        validate(NO_ARGS, false);

        String out = stderr();
        assertTrue(out.contains(WHISPER_BANNER_TITLE), out);
        assertTrue(out.contains(EMBEDDING_BANNER_TITLE), out);
        assertTrue(out.contains(FFMPEG_BANNER_TITLE), out);
    }

    @Test
    @DisplayName("the binaryDir argument does not influence resolution")
    void binaryDirArgumentIsIgnored() {
        // Documents a real property of the signature: the first parameter is never read by the body.
        // Resolution goes through ModelInstaller.resolveModelRoot() and the model-root property instead.
        enableStt();
        try (MockedStatic<LocalAudioTranscoder> ffmpeg = mockStatic(LocalAudioTranscoder.class)) {
            ffmpeg.when(() -> LocalAudioTranscoder.isFfmpegAvailable(any())).thenReturn(true);
            LocalModelValidator.validateLocalModels(Path.of("/definitely/not/a/real/dir"), NO_ARGS);
        }

        assertTrue(stderr().contains(WHISPER_BANNER_TITLE), stderr());
    }
}
