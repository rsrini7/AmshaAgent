package com.amsha.localml.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class LocalAudioTranscoderTest {

    private static byte[] create16kMonoWav(int numSamples, short sampleVal) {
        ByteBuffer bb = ByteBuffer.allocate(44 + numSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[] {'R', 'I', 'F', 'F'});
        bb.putInt(36 + numSamples * 2);
        bb.put(new byte[] {'W', 'A', 'V', 'E'});
        bb.put(new byte[] {'f', 'm', 't', ' '});
        bb.putInt(16);
        bb.putShort((short) 1); // PCM
        bb.putShort((short) 1); // mono
        bb.putInt(16000); // 16kHz
        bb.putInt(32000); // byte rate
        bb.putShort((short) 2); // block align
        bb.putShort((short) 16); // bits per sample
        bb.put(new byte[] {'d', 'a', 't', 'a'});
        bb.putInt(numSamples * 2);
        for (int i = 0; i < numSamples; i++) {
            bb.putShort(sampleVal);
        }
        return bb.array();
    }

    @Test
    void isSupportedAudioMimeValidatesKnownFormats() {
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/wav"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/x-wav"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/wave"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/pcm"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/ogg"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/ogg; codecs=opus"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/opus"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("application/ogg"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/mpeg"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/mp3"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/webm"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("video/webm"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/flac"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/m4a"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/mp4"));
        assertTrue(LocalAudioTranscoder.isSupportedAudioMime("audio/aac"));

        assertFalse(LocalAudioTranscoder.isSupportedAudioMime(null));
        assertFalse(LocalAudioTranscoder.isSupportedAudioMime(""));
        assertFalse(LocalAudioTranscoder.isSupportedAudioMime("   "));
        assertFalse(LocalAudioTranscoder.isSupportedAudioMime("image/png"));
        assertFalse(LocalAudioTranscoder.isSupportedAudioMime("application/json"));
        assertFalse(LocalAudioTranscoder.isSupportedAudioMime("text/plain"));
    }

    @Test
    void getOsInstallGuidanceReturnsActionableCommands() {
        String guidance = LocalAudioTranscoder.getOsInstallGuidance();
        assertNotNull(guidance);
        assertTrue(guidance.contains("ffmpeg"));
    }

    @Test
    void isFfmpegAvailableReturnsFalseForMissingExecutable() {
        assertFalse(LocalAudioTranscoder.isFfmpegAvailable("non_existent_executable_12345"));
    }

    @Test
    void toPcm16kMonoFastPathsStandardWav() {
        byte[] wav = create16kMonoWav(160, (short) 16384);
        float[] samples = LocalAudioTranscoder.toPcm16kMono(wav, "audio/wav", "invalid_ffmpeg", Duration.ofSeconds(5));
        assertEquals(160, samples.length);
        assertEquals(0.5f, samples[0], 0.01f);
    }

    @Test
    void toPcm16kMonoThrowsOnEmptyInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalAudioTranscoder.toPcm16kMono(new byte[0], "audio/ogg", "ffmpeg", Duration.ofSeconds(5)));
    }

    @Test
    void toPcm16kMonoThrowsWithOsGuidanceWhenFfmpegMissing() {
        byte[] fakeOgg = new byte[] {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 0, 0};
        AudioTranscodingException ex = assertThrows(
                AudioTranscodingException.class,
                () -> LocalAudioTranscoder.toPcm16kMono(
                        fakeOgg, "audio/ogg", "non_existent_ffmpeg_xyz", Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("ffmpeg executable not found or not runnable"));
        assertTrue(ex.getMessage().contains("ffmpeg"));
    }
}
