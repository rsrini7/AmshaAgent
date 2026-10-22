package com.amsha.llm.providers.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.amsha.channel.api.MediaTypes;

import org.jboss.logging.Logger;

/** Measures audio duration and normalizes supported non-WAV formats before Gnani egress. */
final class AudioDurationProbe {
    private static final Logger log = Logger.getLogger(AudioDurationProbe.class);
    private static final long SAMPLE_RATE = 16_000;
    private static final long BYTES_PER_SECOND = SAMPLE_RATE * 2;
    private static final long WAV_HEADER_ALLOWANCE = 65_536;
    private static final long MIN_WAV_SAMPLE_RATE = 8_000;
    private static final long MAX_WAV_SAMPLE_RATE = 192_000;
    private static final int MAX_WAV_CHANNELS = 8;
    private static final int MAX_WAV_CHUNKS = 1_024;
    /** Size sentinel written by streamed WAV encoders that never patch the final length. */
    private static final long UNKNOWN_CHUNK_SIZE = 0xFFFF_FFFFL;

    private final Path ffmpegPath;

    AudioDurationProbe(String ffmpegPath) {
        String executable = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
        this.ffmpegPath = Path.of(executable);
    }

    ProbeResult measureAndNormalize(byte[] input, String mimeType, Duration maxDuration, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        if (input == null || input.length == 0) {
            throw new IOException("audio input is empty");
        }
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
        Duration wavDuration = readWavDuration(input);
        if (wavDuration != null) {
            return new ProbeResult(input, wavDuration, MediaTypes.WAV);
        }
        return transcodeAndMeasure(input, mimeType, maxDuration, timeout);
    }

    private ProbeResult transcodeAndMeasure(byte[] input, String mimeType, Duration maxDuration, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        Path inputFile = null;
        Path outputFile = null;
        Process process = null;
        Thread stderrDrainer = null;
        try {
            String suffix = suffixFor(mimeType);
            inputFile = Files.createTempFile("amsha-gnani-audio-", suffix);
            outputFile = Files.createTempFile("amsha-gnani-duration-", ".wav");
            Files.write(inputFile, input);
            Duration probeDuration = maxDuration.plusSeconds(1);
            List<String> command = List.of(
                    ffmpegPath.toString(),
                    "-nostdin",
                    "-y",
                    "-v",
                    "error",
                    "-i",
                    inputFile.toString(),
                    "-t",
                    String.format(Locale.ROOT, "%.3f", probeDuration.toMillis() / 1000.0),
                    "-map",
                    "0:a:0",
                    "-vn",
                    "-ar",
                    Long.toString(SAMPLE_RATE),
                    "-ac",
                    "1",
                    "-c:a",
                    "pcm_s16le",
                    "-f",
                    "wav",
                    outputFile.toString());
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
            stderrDrainer = drainAsync(process.getErrorStream());
            waitForProcess(process, timeout);
            if (process.exitValue() != 0) {
                throw new IOException("ffmpeg could not measure audio duration");
            }
            return readNormalizedAudio(outputFile, maxDuration);
        } finally {
            destroyIfRunning(process);
            joinQuietly(stderrDrainer);
            deleteQuietly(inputFile);
            deleteQuietly(outputFile);
        }
    }

    private static ProbeResult readNormalizedAudio(Path outputFile, Duration maxDuration) throws IOException {
        long maxOutputBytes = maxDuration.plusSeconds(1).toMillis() * (BYTES_PER_SECOND / 1000) + WAV_HEADER_ALLOWANCE;
        long outputSize = Files.size(outputFile);
        if (outputSize > maxOutputBytes || outputSize > Integer.MAX_VALUE) {
            throw new IOException("normalized audio exceeds the duration probe bound");
        }
        byte[] normalized = Files.readAllBytes(outputFile);
        Duration measured = readWavDuration(normalized);
        if (measured == null) {
            throw new IOException("ffmpeg returned an invalid WAV duration probe");
        }
        return new ProbeResult(normalized, measured, MediaTypes.WAV);
    }

    static Duration readWavDuration(byte[] bytes) {
        if (!hasWavHeader(bytes)) {
            return null;
        }
        long declaredRiffSize = unsignedIntLittleEndian(bytes, 4);
        // Streamed WAVs leave the RIFF size unpatched at 0xFFFFFFFF; Gnani Timbre v3.0 does this on every
        // response. The chunk walk below bounds each chunk against the real array length, so a placeholder is
        // not evidence of truncation. A patched size that disagrees with the array still is.
        if (declaredRiffSize != UNKNOWN_CHUNK_SIZE && declaredRiffSize + 8L != bytes.length) {
            return null;
        }
        int formatCode = -1;
        int channels = 0;
        int blockAlign = 0;
        int bitsPerSample = 0;
        long sampleRate = 0;
        long byteRate = 0;
        long dataSize = -1;
        boolean foundFormat = false;
        boolean foundData = false;
        int chunkCount = 0;
        int offset = 12;
        while (offset <= bytes.length - 8) {
            if (++chunkCount > MAX_WAV_CHUNKS) {
                return null;
            }
            long chunkSize = unsignedIntLittleEndian(bytes, offset + 4);
            int chunkData = offset + 8;
            String chunkId = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            long nextOffset;
            if (chunkSize == UNKNOWN_CHUNK_SIZE) {
                // Only the trailing data chunk may legitimately run to end-of-file. An unknown extent on any
                // other chunk leaves no way to locate the next one, so the layout is not usable.
                if (!"data".equals(chunkId)) {
                    return null;
                }
                chunkSize = bytes.length - (long) chunkData;
                nextOffset = bytes.length;
            } else {
                nextOffset = (long) chunkData + chunkSize + (chunkSize & 1L);
            }
            if (nextOffset > bytes.length) {
                return null;
            }
            if ("fmt ".equals(chunkId)) {
                if (foundFormat || chunkSize < 16) {
                    return null;
                }
                foundFormat = true;
                formatCode = unsignedShortLittleEndian(bytes, chunkData);
                channels = unsignedShortLittleEndian(bytes, chunkData + 2);
                sampleRate = unsignedIntLittleEndian(bytes, chunkData + 4);
                byteRate = unsignedIntLittleEndian(bytes, chunkData + 8);
                blockAlign = unsignedShortLittleEndian(bytes, chunkData + 12);
                bitsPerSample = unsignedShortLittleEndian(bytes, chunkData + 14);
            } else if ("data".equals(chunkId)) {
                if (foundData) {
                    return null;
                }
                foundData = true;
                dataSize = chunkSize;
            }
            offset = (int) nextOffset;
        }
        if (offset != bytes.length
                || !foundFormat
                || !foundData
                || dataSize <= 0
                || sampleRate < MIN_WAV_SAMPLE_RATE
                || sampleRate > MAX_WAV_SAMPLE_RATE
                || channels < 1
                || channels > MAX_WAV_CHANNELS
                || !isSupportedWavSampleFormat(formatCode, bitsPerSample)
                || bitsPerSample % Byte.SIZE != 0) {
            return null;
        }
        int expectedBlockAlign = channels * (bitsPerSample / Byte.SIZE);
        if (blockAlign != expectedBlockAlign || byteRate != sampleRate * blockAlign || dataSize % blockAlign != 0) {
            return null;
        }
        long wholeSeconds = dataSize / byteRate;
        long remainingBytes = dataSize % byteRate;
        long nanoseconds = (remainingBytes * 1_000_000_000L + byteRate - 1) / byteRate;
        if (nanoseconds == 1_000_000_000L) {
            wholeSeconds++;
            nanoseconds = 0;
        }
        return Duration.ofSeconds(wholeSeconds, nanoseconds);
    }

    private static boolean isSupportedWavSampleFormat(int formatCode, int bitsPerSample) {
        return formatCode == 1
                        && (bitsPerSample == 8 || bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32)
                || formatCode == 3 && (bitsPerSample == 32 || bitsPerSample == 64);
    }

    private static int unsignedShortLittleEndian(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
    }

    private static long unsignedIntLittleEndian(byte[] bytes, int offset) {
        return (long) Byte.toUnsignedInt(bytes[offset])
                | ((long) Byte.toUnsignedInt(bytes[offset + 1]) << 8)
                | ((long) Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                | ((long) Byte.toUnsignedInt(bytes[offset + 3]) << 24);
    }

    private static boolean hasWavHeader(byte[] bytes) {
        return bytes != null
                && bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'A'
                && bytes[10] == 'V'
                && bytes[11] == 'E';
    }

    static String suffixFor(String mimeType) {
        String normalized =
                mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case MediaTypes.WAV, MediaTypes.X_WAV, MediaTypes.WAVE -> ".wav";
            case MediaTypes.OGG, MediaTypes.APPLICATION_OGG, MediaTypes.OPUS -> ".ogg";
            case MediaTypes.MPEG, MediaTypes.MP3 -> ".mp3";
            case MediaTypes.FLAC, MediaTypes.X_FLAC -> ".flac";
            case MediaTypes.AAC -> ".aac";
            case MediaTypes.M4A, MediaTypes.X_M4A, MediaTypes.MP4 -> ".m4a";
            default -> ".audio";
        };
    }

    private static Thread drainAsync(InputStream stream) {
        Thread drainer = Thread.ofVirtual().unstarted(() -> {
            try (InputStream input = stream) {
                input.transferTo(OutputStream.nullOutputStream());
            } catch (IOException _) {
                // Discarded process diagnostics are intentionally not logged.
            }
        });
        drainer.start();
        return drainer;
    }

    private static void waitForProcess(Process process, Duration timeout)
            throws InterruptedException, TimeoutException {
        Duration effectiveTimeout =
                timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        if (!process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("audio duration measurement timed out");
        }
    }

    private static void destroyIfRunning(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            log.warn("Unable to delete temporary Gnani audio processing file; manual cleanup may be needed.");
        }
    }

    record ProbeResult(byte[] audioBytes, Duration measuredDuration, String mimeType) {}
}
