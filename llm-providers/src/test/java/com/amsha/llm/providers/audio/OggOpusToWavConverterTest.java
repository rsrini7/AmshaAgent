package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class OggOpusToWavConverterTest {

    private static final byte[] OGG_BYTES = {'O', 'g', 'g', 'S', 1, 2, 3, 4};
    private static final byte[] WAV_BYTES = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 1, 2};
    private static final byte[] OTHER_BYTES = {'P', 'K', 3, 4, 1, 2, 3, 4};

    @Test
    void wavPassesThroughUnchangedByMime() throws Exception {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");
        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(WAV_BYTES, "audio/wav", 25 * 1024 * 1024, Duration.ofSeconds(30));
        assertArrayEquals(WAV_BYTES, result.bytes());
        assertEquals("audio/wav", result.mimeType());
    }

    @Test
    void wavPassesThroughUnchangedByMagic() throws Exception {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");
        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(WAV_BYTES, null, 25 * 1024 * 1024, Duration.ofSeconds(30));
        assertArrayEquals(WAV_BYTES, result.bytes());
        assertEquals("audio/wav", result.mimeType());
    }

    @Test
    void nonOggPassesThroughUnchanged() throws Exception {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");
        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(OTHER_BYTES, "application/zip", 25 * 1024 * 1024, Duration.ofSeconds(30));
        assertArrayEquals(OTHER_BYTES, result.bytes());
        assertEquals("application/zip", result.mimeType());
    }

    @Test
    void oggOpusWithMissingFfmpegThrowsCategorical() {
        OggOpusToWavConverter converter = new OggOpusToWavConverter("/nonexistent/ffmpeg-bin");
        AudioConversionException ex = assertThrows(
                AudioConversionException.class,
                () -> converter.convertIfNeeded(OGG_BYTES, "audio/ogg", 25 * 1024 * 1024, Duration.ofSeconds(30)));
        assertTrue(ex.getMessage().contains("ffmpeg"), ex.getMessage());
    }

    @Test
    void oggOpusIsTranscodedToMono16kWavWhenFfmpegPresent() throws Exception {
        assumeTrue(ffmpegPresent(), "ffmpeg not on PATH; skipping live transcode");
        OggOpusToWavConverter converter = new OggOpusToWavConverter("ffmpeg");

        byte[] ogg = synthesizeOgg();
        OggOpusToWavConverter.ConvertedAudio result =
                converter.convertIfNeeded(ogg, "audio/ogg", 25 * 1024 * 1024, Duration.ofSeconds(60));

        assertEquals("audio/wav", result.mimeType());
        byte[] wav = result.bytes();
        assertTrue(wav.length > 12, "transcoded WAV is empty");
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);
    }

    private static boolean ffmpegPresent() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            p.destroyForcibly();
            return done;
        } catch (IOException | InterruptedException _) {
            return false;
        }
    }

    /** Builds a minimal valid OGG/Opus file by asking ffmpeg to synthesize a short tone,
     *  using the same Opus codec Telegram voice notes use. */
    private static byte[] synthesizeOgg() throws IOException, InterruptedException {
        Path ogg = Files.createTempFile("amsha-oggtest-out-", ".ogg");
        try {
            List<String> cmd = List.of(
                    "ffmpeg",
                    "-y",
                    "-loglevel",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:duration=0.2",
                    "-c:a",
                    "libopus",
                    ogg.toString());
            Process p = new ProcessBuilder(cmd).start();
            boolean done = p.waitFor(30, TimeUnit.SECONDS);
            p.destroyForcibly();
            if (!done || p.exitValue() != 0) {
                throw new IOException("ffmpeg ogg synthesis failed");
            }
            byte[] bytes = Files.readAllBytes(ogg);
            assertTrue(bytes.length >= 4, "synthesized OGG is empty");
            assertEquals('O', (char) bytes[0]);
            assertEquals('g', (char) bytes[1]);
            assertEquals('g', (char) bytes[2]);
            assertEquals('S', (char) bytes[3]);
            return bytes;
        } finally {
            Files.deleteIfExists(ogg);
        }
    }
}
