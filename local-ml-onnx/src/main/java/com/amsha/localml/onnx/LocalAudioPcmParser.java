package com.amsha.localml.onnx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Pure Java parser that extracts normalized [-1.0, 1.0] float PCM audio samples
 * from RIFF/WAV or raw 16-bit 16-kHz mono PCM byte streams.
 */
public final class LocalAudioPcmParser {

    private LocalAudioPcmParser() {}

    /**
     * Parses WAV or raw 16-bit PCM to normalized [-1.0, 1.0] float array.
     */
    public static float[] parseAudioPcm(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        int offset = 0;
        int dataLen = bytes.length;
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'A'
                && bytes[10] == 'V'
                && bytes[11] == 'E') {
            ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 12;
            boolean fmtFound = false;
            boolean dataFound = false;
            while (pos + 8 <= bytes.length) {
                int chunkId = bb.getInt(pos);
                int chunkSize = bb.getInt(pos + 4);
                pos += 8;
                if (chunkSize < 0 || pos + chunkSize > bytes.length) {
                    throw new IllegalArgumentException("Malformed WAV chunk size at position " + (pos - 8));
                }
                if (chunkId == 0x20746d66) { // "fmt "
                    if (chunkSize < 16) {
                        throw new IllegalArgumentException("WAV fmt chunk too small: " + chunkSize);
                    }
                    short audioFormat = bb.getShort(pos);
                    short numChannels = bb.getShort(pos + 2);
                    int sampleRate = bb.getInt(pos + 4);
                    short bitsPerSample = bb.getShort(pos + 14);
                    if (audioFormat != 1 || numChannels != 1 || sampleRate != 16000 || bitsPerSample != 16) {
                        throw new IllegalArgumentException(String.format(
                                "Unsupported WAV format: format=%d, channels=%d, rate=%d, bits=%d (expected PCM 16kHz mono 16-bit)",
                                audioFormat, numChannels, sampleRate, bitsPerSample));
                    }
                    fmtFound = true;
                } else if (chunkId == 0x61746164) { // "data"
                    offset = pos;
                    dataLen = chunkSize;
                    dataFound = true;
                    break;
                }
                pos += (chunkSize + 1) & ~1;
            }
            if (!fmtFound || !dataFound) {
                throw new IllegalArgumentException("Incomplete WAV: missing fmt or data chunk");
            }
        }
        int remaining = Math.min(dataLen, bytes.length - offset);
        if (remaining % 2 != 0 || remaining <= 0) {
            throw new IllegalArgumentException("Audio byte count is invalid for 16-bit PCM: " + remaining);
        }
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int numSamples = remaining / 2;
        float[] pcm = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            pcm[i] = bb.getShort(offset + i * 2) / 32768.0f;
        }
        return pcm;
    }
}
