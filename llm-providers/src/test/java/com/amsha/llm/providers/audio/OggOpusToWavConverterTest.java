package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link OggOpusToWavConverter} against a <em>fake</em> ffmpeg executable rather than the
 * real binary.
 *
 * <p>The previous revision gated both of its transcoding tests behind
 * {@code assumeTrue(ffmpegPresent(), ...)}. On any machine without ffmpeg on the PATH that reported
 * as <em>skipped</em>, so the whole {@code transcode} body — process start, the stderr drainer, the
 * timeout and exit-code arms, the payload bound, the read-back and the temp-file cleanup — was never
 * executed and covered nothing. A shell script written into a {@link TempDir} and handed to the
 * constructor (production only ever calls {@code toString()} on the configured path, never
 * {@code toFile()} or an existence check) makes every branch deterministic and machine-independent,
 * and lets the failure arms be driven directly instead of waiting for a real ffmpeg to misbehave.
 */
class OggOpusToWavConverterTest {

    private static final byte[] OGG_BYTES = {'O', 'g', 'g', 'S', 1, 2, 3, 4};
    private static final byte[] WAV_BYTES = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 1, 2};
    private static final byte[] WEBM_BYTES = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2, 3, 4};
    private static final byte[] OTHER_BYTES = {'P', 'K', 3, 4, 1, 2, 3, 4};
    private static final long GENEROUS_LIMIT = 25L * 1024 * 1024;

    /** Writes a small WAV payload to the output path (always the last argument) and exits 0. */
    private static final String SCRIPT_WRITES_WAV = """
            for last in "$@"; do :; done
            printf 'RIFF0000WAVE' > "$last"
            echo 'fake ffmpeg: transcoded' >&2
            exit 0
            """;

    /** Exits non-zero without producing any output. */
    private static final String SCRIPT_EXITS_NONZERO = """
            echo 'fake ffmpeg: boom' >&2
            exit 3
            """;

    /**
     * Never finishes on its own. {@code exec} replaces the shell so the process <em>is</em> the
     * sleeper, which means a forced kill closes the pipes and lets the stderr drainer finish.
     */
    private static final String SCRIPT_HANGS = """
            exec sleep 5
            """;

    /**
     * As {@link #SCRIPT_HANGS}, but a detached grandchild keeps stderr open so the drainer thread is
     * still parked when the timeout branch joins it. {@code destroyForcibly()} reaps only the direct
     * child, so without this the drainer could already have observed EOF and the join would return
     * before it could be interrupted.
     */
    private static final String SCRIPT_HANGS_WITH_HELD_PIPE = """
            sleep 5 &
            exec sleep 5
            """;

    /** Writes more than any test limit so the payload bound trips. */
    private static final String SCRIPT_WRITES_OVERSIZED = """
            for last in "$@"; do :; done
            dd if=/dev/zero of="$last" bs=1024 count=8 2>/dev/null
            exit 0
            """;

    /** Replaces the output path with a non-empty directory so the cleanup delete fails. */
    private static final String SCRIPT_OUTPUT_IS_A_DIRECTORY = """
            for last in "$@"; do :; done
            rm -f "$last"
            mkdir "$last"
            echo 'kept' > "$last/keep"
            exit 0
            """;

    @TempDir
    Path tempDir;

    @Test
    void wavPassesThroughUnchangedByMime() throws Exception {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");
        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(WAV_BYTES, "audio/wav", GENEROUS_LIMIT, Duration.ofSeconds(30));
        assertArrayEquals(WAV_BYTES, result.bytes());
        assertEquals("audio/wav", result.mimeType());
    }

    @Test
    void wavPassesThroughUnchangedByMagic() throws Exception {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");
        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(WAV_BYTES, null, GENEROUS_LIMIT, Duration.ofSeconds(30));
        assertArrayEquals(WAV_BYTES, result.bytes());
        assertEquals("audio/wav", result.mimeType());
    }

    @Test
    void nonOggPassesThroughUnchanged() throws Exception {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");
        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(OTHER_BYTES, "application/zip", GENEROUS_LIMIT, Duration.ofSeconds(30));
        assertArrayEquals(OTHER_BYTES, result.bytes());
        assertEquals("application/zip", result.mimeType());
    }

    @Test
    void nullAndEmptyInputPassThroughUntouched() throws Exception {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");

        OggOpusToWavConverter.ConvertedAudio nullResult =
                converter.convertIfNeeded(null, "audio/ogg", GENEROUS_LIMIT, Duration.ofSeconds(30));
        assertNull(nullResult.bytes());
        assertEquals("audio/ogg", nullResult.mimeType());

        OggOpusToWavConverter.ConvertedAudio emptyResult =
                converter.convertIfNeeded(new byte[0], "audio/ogg", GENEROUS_LIMIT, Duration.ofSeconds(30));
        assertEquals(0, emptyResult.bytes().length);
        assertEquals("audio/ogg", emptyResult.mimeType());
    }

    @Test
    void isWavContainerDetectsRiffWaveMagicOnly() {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");
        assertTrue(converter.isWavContainer(WAV_BYTES));
        assertFalse(converter.isWavContainer(OGG_BYTES));
        assertFalse(converter.isWavContainer(WEBM_BYTES));
        // A RIFF container that is not WAVE, and a header truncated before the WAVE marker.
        assertFalse(converter.isWavContainer(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', 0}));
        assertFalse(converter.isWavContainer(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0}));
    }

    @Test
    void blankOrNullFfmpegPathFallsBackToTheDefaultExecutableName() {
        assertFalse(new OggOpusToWavConverter(null).isWavContainer(OGG_BYTES));
        assertFalse(new OggOpusToWavConverter("   ").isWavContainer(OGG_BYTES));
        assertTrue(new OggOpusToWavConverter("  ffmpeg  ").isWavContainer(WAV_BYTES));
    }

    @Test
    void oggOpusWithMissingFfmpegThrowsCategorical() {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("/nonexistent/ffmpeg-bin");
        AudioConversionException ex = assertThrows(
                AudioConversionException.class,
                () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, Duration.ofSeconds(30)));
        assertTrue(ex.getMessage().contains("ffmpeg"), ex.getMessage());
    }

    @Test
    void oggOpusIsTranscodedToWavByTheConfiguredExecutable() throws Exception {
        Path ffmpeg = fakeFfmpeg("writes-wav-ogg", SCRIPT_WRITES_WAV);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, Duration.ofSeconds(30));

        assertEquals("audio/wav", result.mimeType());
        assertTrue(converter.isWavContainer(result.bytes()), "converted payload is not a WAV container");
        assertEquals(12, result.bytes().length);
    }

    @Test
    void webmOpusIsTranscodedThroughTheWebmSuffix() throws Exception {
        Path ffmpeg = fakeFfmpeg("writes-wav-webm", SCRIPT_WRITES_WAV);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(WEBM_BYTES, "audio/webm", GENEROUS_LIMIT, Duration.ofSeconds(30));

        assertEquals("audio/wav", result.mimeType());
        assertTrue(converter.isWavContainer(result.bytes()));
    }

    @Test
    void webmDetectedByMagicAloneAlsoTranscodes() throws Exception {
        Path ffmpeg = fakeFfmpeg("writes-wav-webm-magic", SCRIPT_WRITES_WAV);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(WEBM_BYTES, null, GENEROUS_LIMIT, Duration.ofSeconds(30));

        assertEquals("audio/wav", result.mimeType());
    }

    @Test
    void nullZeroAndNegativeTimeoutsFallBackToTheDefault() throws Exception {
        Path ffmpeg = fakeFfmpeg("writes-wav-timeouts", SCRIPT_WRITES_WAV);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        for (Duration timeout : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-5)}) {
            OggOpusToWavConverter.ConvertedAudio result =
                    converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, timeout);
            assertEquals("audio/wav", result.mimeType(), "timeout " + timeout + " did not use the default");
        }
    }

    @Test
    void nonZeroExitIsReportedCategorically() throws Exception {
        Path ffmpeg = fakeFfmpeg("exits-nonzero", SCRIPT_EXITS_NONZERO);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        AudioConversionException failure = assertThrows(
                AudioConversionException.class,
                () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, Duration.ofSeconds(30)));

        assertTrue(failure.getMessage().contains("exited 3"), failure.getMessage());
    }

    @Test
    void oversizedOutputIsRejectedBeforeItIsReadIntoMemory() throws Exception {
        Path ffmpeg = fakeFfmpeg("writes-oversized", SCRIPT_WRITES_OVERSIZED);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        AudioConversionException failure = assertThrows(
                AudioConversionException.class,
                () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", 100, Duration.ofSeconds(30)));

        assertTrue(failure.getMessage().contains("exceeding limit 100"), failure.getMessage());
    }

    @Test
    void timeoutKillsTheChildAndFailsCategorically() throws Exception {
        Path ffmpeg = fakeFfmpeg("hangs", SCRIPT_HANGS);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        // Duration.ofNanos(1).toMillis() == 0, so waitFor(0, MILLISECONDS) returns false at once
        // instead of parking the thread for a real millisecond.
        AudioConversionException failure = assertThrows(
                AudioConversionException.class,
                () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, Duration.ofNanos(1)));

        assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
    }

    @Test
    void interruptedWaitIsReportedAsAnInterruption() throws Exception {
        Path ffmpeg = fakeFfmpeg("hangs-interrupt-wait", SCRIPT_HANGS);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        Thread.currentThread().interrupt();
        try {
            AudioConversionException failure = assertThrows(
                    AudioConversionException.class,
                    () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, Duration.ofSeconds(30)));
            assertTrue(failure.getMessage().contains("interrupted"), failure.getMessage());
        } finally {
            // Surefire reuses this JVM for the rest of the module, so never leak the flag.
            Thread.interrupted();
        }
    }

    @Test
    void interruptRacingTheTimeoutIsAbsorbedByTheCleanupJoin() throws Exception {
        Path ffmpeg = fakeFfmpeg("hangs-interrupt-join", SCRIPT_HANGS_WITH_HELD_PIPE);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        Thread.currentThread().interrupt();
        try {
            // waitFor(0, ...) short-circuits before the interruptible park, so the flag survives
            // into the timeout branch and the joinQuietly(...) that reaps the drainer.
            AudioConversionException failure = assertThrows(
                    AudioConversionException.class,
                    () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, Duration.ofNanos(1)));
            assertTrue(failure.getMessage().contains("timed out"), failure.getMessage());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void outputPathThatCannotBeCleanedUpDoesNotMaskTheConversionFailure() throws Exception {
        Path ffmpeg = fakeFfmpeg("output-is-a-directory", SCRIPT_OUTPUT_IS_A_DIRECTORY);
        OggOpusToWavConverter converter = new OggOpusToWavConverter(ffmpeg.toString());

        AudioConversionException failure = assertThrows(
                AudioConversionException.class,
                () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", GENEROUS_LIMIT, Duration.ofSeconds(30)));

        assertTrue(failure.getMessage().contains("io error"), failure.getMessage());
    }

    private Path fakeFfmpeg(String name, String body) throws IOException {
        Path script = tempDir.resolve(name + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        assertTrue(script.toFile().setExecutable(true), "could not mark " + script + " executable");
        return script;
    }
}
