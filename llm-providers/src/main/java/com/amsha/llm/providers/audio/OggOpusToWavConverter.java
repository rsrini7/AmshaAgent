package com.amsha.llm.providers.audio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Transcodes OGG/Opus voice audio to mono 16 kHz PCM WAV using an allowlisted ffmpeg invocation
 * via {@link ProcessBuilder} (never a shell). WAV input passes through unchanged.
 *
 * <p>Detection is by MIME type and magic bytes only; the ffmpeg executable path comes from
 * configuration, never from the attachment, so there is no injection surface. Conversion honours
 * the same payload bound and timeout as the rest of the STT pipeline, and all temporary files are
 * removed in a {@code finally} block. When ffmpeg is missing or the transcode fails, an
 * {@link AudioConversionException} is raised so the caller can degrade safely.</p>
 */
final class OggOpusToWavConverter {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final byte[] OGG_MAGIC = {'O', 'g', 'g', 'S'};
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WAVE_MAGIC = {'W', 'A', 'V', 'E'};

    private final Path ffmpegPath;

    OggOpusToWavConverter(String ffmpegPath) {
        String resolved = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.trim();
        this.ffmpegPath = Path.of(resolved);
    }

    /**
     * Returns audio unchanged when it is already WAV or is not OGG/Opus. Otherwise transcodes
     * OGG/Opus to mono 16 kHz PCM WAV.
     */
    ConvertedAudio convertIfNeeded(byte[] input, String mimeType, long maxBytes, Duration timeout)
            throws AudioConversionException {
        if (input == null || input.length == 0) {
            return new ConvertedAudio(input, mimeType);
        }
        if (isWav(mimeType, input)) {
            String outMime = (mimeType == null || mimeType.isBlank()) ? "audio/wav" : mimeType;
            return new ConvertedAudio(input, outMime);
        }
        if (!isOggOpus(mimeType, input)) {
            return new ConvertedAudio(input, mimeType);
        }
        return transcode(input, maxBytes, timeout);
    }

    /** True when the bytes are already a WAV container (any MIME) — used by callers to verify output. */
    boolean isWavContainer(byte[] input) {
        return isWav(null, input);
    }

    private ConvertedAudio transcode(byte[] input, long maxBytes, Duration timeout) throws AudioConversionException {
        Path in = null;
        Path out = null;
        try {
            in = Files.createTempFile("amsha-stt-in-", ".ogg");
            out = Files.createTempFile("amsha-stt-out-", ".wav");
            Files.write(in, input);
            List<String> command = List.of(
                    ffmpegPath.toString(),
                    "-y",
                    "-loglevel",
                    "error",
                    "-i",
                    in.toString(),
                    "-ar",
                    "16000",
                    "-ac",
                    "1",
                    "-c:a",
                    "pcm_s16le",
                    "-fs",
                    String.valueOf(maxBytes),
                    "-f",
                    "wav",
                    out.toString());
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(false);
            Process process;
            try {
                process = processBuilder.start();
            } catch (IOException e) {
                throw new AudioConversionException("ffmpeg unavailable: " + ffmpegPath, e);
            }
            // Drain stderr in the background so a chatty ffmpeg cannot fill the pipe and deadlock.
            Thread stderrDrainer = drainAsync(process.getErrorStream());
            boolean finished;
            Duration effectiveTimeout =
                    timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
            try {
                finished = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException _) {
                process.destroyForcibly();
                joinQuietly(stderrDrainer);
                Thread.currentThread().interrupt();
                throw new AudioConversionException("ffmpeg interrupted");
            }
            if (!finished) {
                process.destroyForcibly();
                joinQuietly(stderrDrainer);
                throw new AudioConversionException("ffmpeg timed out after " + effectiveTimeout);
            }
            joinQuietly(stderrDrainer);
            if (process.exitValue() != 0) {
                throw new AudioConversionException("ffmpeg exited " + process.exitValue());
            }
            // Enforce the payload bound BEFORE reading the output into memory: a small compressed
            // OGG can expand to a very large WAV, so stat the file first and refuse oversized output.
            long produced = Files.size(out);
            if (produced > maxBytes) {
                throw new AudioConversionException(
                        "ffmpeg produced " + produced + " bytes, exceeding limit " + maxBytes);
            }
            return new ConvertedAudio(Files.readAllBytes(out), "audio/wav");
        } catch (IOException e) {
            throw new AudioConversionException("ffmpeg io error: " + e.getMessage(), e);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static Thread drainAsync(InputStream stream) {
        Thread drainer = new Thread(() -> {
            try (InputStream in = stream) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException _) {
                // best-effort drain to avoid blocking the child process
            }
        });
        drainer.setDaemon(true);
        drainer.start();
        return drainer;
    }

    private static void joinQuietly(Thread drainer) {
        if (drainer == null) {
            return;
        }
        try {
            drainer.join(TimeUnit.SECONDS.toMillis(5));
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
            // best-effort: temp file cleanup may legitimately race with the OS
        }
    }

    private static boolean isWav(String mimeType, byte[] input) {
        if (mimeType != null) {
            String mime = mimeType.toLowerCase(Locale.ROOT);
            if (mime.equals("audio/wav") || mime.equals("audio/x-wav") || mime.equals("audio/wave")) {
                return true;
            }
        }
        return input.length >= 12 && startsWith(input, 0, RIFF_MAGIC) && startsWith(input, 8, WAVE_MAGIC);
    }

    private static boolean isOggOpus(String mimeType, byte[] input) {
        if (mimeType != null) {
            String mime = mimeType.toLowerCase(Locale.ROOT);
            if (mime.equals("audio/ogg") || mime.equals("audio/opus") || mime.equals("application/ogg")) {
                return true;
            }
        }
        return input.length >= 4 && startsWith(input, 0, OGG_MAGIC);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] magic) {
        if (data.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** Result of a conversion attempt: possibly-transcoded bytes plus the effective MIME type. */
    record ConvertedAudio(byte[] bytes, String mimeType) {}
}
