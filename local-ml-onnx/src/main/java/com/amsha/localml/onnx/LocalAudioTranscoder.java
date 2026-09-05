package com.amsha.localml.onnx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

/**
 * Transcodes diverse audio formats (OGG/Opus, WebM, MP3, AAC, non-standard WAV)
 * to 16 kHz mono 16-bit PCM WAV using an allowlisted ffmpeg invocation via {@link ProcessBuilder}.
 * Passes standard 16 kHz mono 16-bit PCM WAV through directly without spawning processes.
 */
public final class LocalAudioTranscoder {

    private static final Logger log = Logger.getLogger(LocalAudioTranscoder.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final Set<String> SUPPORTED_AUDIO_MIMES = Set.of(
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/pcm",
            "audio/ogg",
            "audio/opus",
            "application/ogg",
            "audio/mpeg",
            "audio/mp3",
            "audio/webm",
            "video/webm",
            "audio/flac",
            "audio/x-flac",
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "audio/aac");

    private LocalAudioTranscoder() {}

    /**
     * Returns true if the given MIME type is recognized as an audio format
     * supported directly or via ffmpeg transcoding.
     */
    public static boolean isSupportedAudioMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        String base = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_AUDIO_MIMES.contains(base)
                || base.startsWith("audio/")
                || "video/webm".equals(base)
                || "application/ogg".equals(base);
    }

    /**
     * Returns OS-dependent guidance on installing ffmpeg.
     */
    public static String getOsInstallGuidance() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return "Install ffmpeg via Homebrew:\n    brew install ffmpeg";
        } else if (os.contains("win")) {
            return "Install ffmpeg via winget or Chocolatey:\n    winget install Gyan.FFmpeg\n    # or: choco install ffmpeg";
        } else {
            return "Install ffmpeg via your package manager:\n    sudo apt update && sudo apt install -y ffmpeg   # Debian/Ubuntu\n    sudo dnf install ffmpeg                         # Fedora/RHEL";
        }
    }

    /**
     * Checks if ffmpeg is present and runnable.
     */
    public static boolean isFfmpegAvailable(String ffmpegPath) {
        String exe = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.trim();
        try {
            Process process = new ProcessBuilder(exe, "-version")
                    .redirectErrorStream(true)
                    .start();
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * Parses the audio into 16 kHz mono normalized float PCM samples.
     * If the input is already a valid 16 kHz mono 16-bit WAV, it is parsed directly in memory.
     * Otherwise, it is transcoded to 16 kHz mono PCM WAV using ffmpeg.
     *
     * @param audioBytes the audio payload
     * @param mimeType provider- or channel-declared MIME type
     * @param ffmpegPath configured ffmpeg executable path or "ffmpeg"
     * @param timeout maximum time allowed for transcoding
     * @return normalized [-1.0, 1.0] float array
     * @throws AudioTranscodingException if ffmpeg is missing, times out, or fails
     * @throws IllegalArgumentException if the audio data is empty or invalid
     */
    public static float[] toPcm16kMono(byte[] audioBytes, String mimeType, String ffmpegPath, Duration timeout) {
        Objects.requireNonNull(audioBytes, "audioBytes must not be null");
        if (audioBytes.length == 0) {
            throw new IllegalArgumentException("Audio payload is empty");
        }

        // 1. Fast-path: check if it's already a valid 16 kHz mono 16-bit WAV
        if (isWavHeader(audioBytes)) {
            try {
                return LocalAudioPcmParser.parseAudioPcm(audioBytes);
            } catch (IllegalArgumentException _) {
                // Non-standard WAV (e.g. 44.1kHz stereo): transcode via ffmpeg below
            }
        }

        // 2. Transcode using ffmpeg
        byte[] transcodedWav = transcodeWithFfmpeg(audioBytes, mimeType, ffmpegPath, timeout);
        return LocalAudioPcmParser.parseAudioPcm(transcodedWav);
    }

    private static boolean isWavHeader(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'A'
                && bytes[10] == 'V'
                && bytes[11] == 'E';
    }

    private static byte[] transcodeWithFfmpeg(byte[] input, String mimeType, String ffmpegPath, Duration timeout) {
        String exe = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath.trim();
        Path in = null;
        Path out = null;
        try {
            String suffix = resolveSuffix(mimeType, input);
            in = Files.createTempFile("amsha-stt-in-", suffix);
            out = Files.createTempFile("amsha-stt-out-", ".wav");
            Files.write(in, input);

            List<String> command = List.of(
                    exe,
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
                    "-f",
                    "wav",
                    out.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            Process process = startProcess(pb, exe);

            ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
            Thread drainer = Thread.ofVirtual().unstarted(() -> {
                try (InputStream err = process.getErrorStream()) {
                    err.transferTo(stderrBuf);
                } catch (IOException e) {
                    log.tracef("Failed to drain stderr: %s", LogUtil.rootMessage(e));
                }
            });
            drainer.start();

            waitForProcess(process, drainer, timeout);

            if (process.exitValue() != 0) {
                String err = stderrBuf.toString(StandardCharsets.UTF_8).trim();
                log.warnf("ffmpeg exited %d: %s", process.exitValue(), err);
                throw new AudioTranscodingException("ffmpeg transcode failed with exit code " + process.exitValue()
                        + (err.isBlank() ? "" : ": " + err));
            }

            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new AudioTranscodingException("ffmpeg transcode I/O error: " + LogUtil.rootMessage(e), e);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static Process startProcess(ProcessBuilder pb, String exe) {
        try {
            return pb.start();
        } catch (IOException e) {
            String guidance = getOsInstallGuidance();
            log.warnf("ffmpeg not available or not executable at '%s': %s\n%s", exe, LogUtil.rootMessage(e), guidance);
            throw new AudioTranscodingException(
                    "ffmpeg executable not found or not runnable: '" + exe + "'.\n" + guidance, e);
        }
    }

    private static void waitForProcess(Process process, Thread drainer, Duration timeout) {
        Duration effectiveTimeout =
                (timeout == null || timeout.isZero() || timeout.isNegative()) ? DEFAULT_TIMEOUT : timeout;
        boolean finished;
        try {
            finished = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException _) {
            process.destroyForcibly();
            joinQuietly(drainer);
            Thread.currentThread().interrupt();
            throw new AudioTranscodingException("ffmpeg transcode interrupted");
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(drainer);
            throw new AudioTranscodingException("ffmpeg transcode timed out after " + effectiveTimeout);
        }

        joinQuietly(drainer);
    }

    private static String resolveSuffix(String mimeType, byte[] bytes) {
        if (mimeType != null) {
            String lower = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (lower.contains("ogg") || lower.contains("opus")) return ".ogg";
            if (lower.contains("mpeg") || lower.contains("mp3")) return ".mp3";
            if (lower.contains("webm")) return ".webm";
            if (lower.contains("wav")) return ".wav";
            if (lower.contains("flac")) return ".flac";
            if (lower.contains("m4a") || lower.contains("mp4") || lower.contains("aac")) return ".m4a";
        }
        if (bytes != null && bytes.length >= 4) {
            if (bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S') return ".ogg";
            if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return ".wav";
            if (bytes[0] == 0x1A && bytes[1] == 0x45 && (bytes[2] & 0xFF) == 0xDF && (bytes[3] & 0xFF) == 0xA3)
                return ".webm";
            if (bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C') return ".flac";
            if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') return ".mp3";
        }
        return ".bin";
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(1000);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.tracef("Failed to delete temp file: %s", LogUtil.rootMessage(e));
        }
    }
}
