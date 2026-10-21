package com.amsha.llm.providers.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class AudioDurationProbeTest {
    @Test
    void measuresPcmWavFromItsDeclaredDataChunk() throws Exception {
        byte[] wav = wavWithDuration(Duration.ofSeconds(31));

        assertEquals(Duration.ofSeconds(31), AudioDurationProbe.readWavDuration(wav));

        AudioDurationProbe.ProbeResult result = new AudioDurationProbe("missing-ffmpeg")
                .measureAndNormalize(wav, "audio/wav", Duration.ofSeconds(30), Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(31), result.measuredDuration());
        assertEquals("audio/wav", result.mimeType());
        assertSame(wav, result.audioBytes());
    }

    @Test
    void rejectsMalformedWavDurationHeaders() {
        assertNull(AudioDurationProbe.readWavDuration(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'}));
    }

    @Test
    void keepsTheWaveExtensionWhenDurationNeedsFfmpegFallback() {
        assertEquals(".wav", AudioDurationProbe.suffixFor("audio/wav"));
    }

    @Test
    void rejectsTamperedByteRateThatWouldUnderstateDuration() {
        byte[] wav = wavWithDuration(Duration.ofSeconds(31));
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 320_000);

        assertNull(AudioDurationProbe.readWavDuration(wav));
        assertThrows(
                IOException.class,
                () -> new AudioDurationProbe("missing-ffmpeg")
                        .measureAndNormalize(wav, "audio/wav", Duration.ofSeconds(30), Duration.ofSeconds(1)));
    }

    @Test
    void rejectsWavWithUnaccountedTrailingBytesAfterDataChunk() {
        byte[] wav = wavWithDuration(Duration.ofSeconds(1));
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 31_996);

        assertNull(AudioDurationProbe.readWavDuration(wav));
    }

    @Test
    void measuresStreamedWavWithUnpatchedRiffAndDataSizes() throws Exception {
        byte[] wav = streamedWavWithDuration(Duration.ofSeconds(2));

        assertEquals(Duration.ofSeconds(2), AudioDurationProbe.readWavDuration(wav));

        // No ffmpeg fallback: the streamed file must be measured directly from its data chunk extent.
        AudioDurationProbe.ProbeResult result = new AudioDurationProbe("missing-ffmpeg")
                .measureAndNormalize(wav, "audio/wav", Duration.ofSeconds(30), Duration.ofSeconds(1));
        assertEquals(Duration.ofSeconds(2), result.measuredDuration());
        assertSame(wav, result.audioBytes());
    }

    @Test
    void rejectsUnknownChunkSizeOnANonDataChunk() {
        byte[] wav = streamedWavWithDuration(Duration.ofSeconds(1));
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0xFFFF_FFFF);

        assertNull(AudioDurationProbe.readWavDuration(wav));
    }

    @Test
    void rejectsPatchedRiffSizeThatDisagreesWithTheArrayLength() {
        byte[] wav = streamedWavWithDuration(Duration.ofSeconds(1));
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 36 + 31_996);

        assertNull(AudioDurationProbe.readWavDuration(wav));
    }

    static byte[] wavWithDuration(Duration duration) {
        int dataSize = Math.toIntExact(duration.toSeconds() * 32_000);
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(16_000);
        buffer.putInt(32_000);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);
        return buffer.array();
    }

    /** Mirrors a streamed WAV: the RIFF size and the trailing data size both stay at the sentinel. */
    static byte[] streamedWavWithDuration(Duration duration) {
        int dataSize = Math.toIntExact(duration.toSeconds() * 32_000);
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(0xFFFF_FFFF);
        buffer.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(16_000);
        buffer.putInt(32_000);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(0xFFFF_FFFF);
        return buffer.array();
    }
}
