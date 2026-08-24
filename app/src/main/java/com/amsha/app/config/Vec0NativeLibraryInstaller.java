package com.amsha.app.config;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.amsha.common.util.LogUtil;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Ensures the correct sqlite-vec native binary for the current OS/arch is
 * available before first SQLite use.
 *
 * <p>Resolution order: reuse an existing binary at the configured path,
 * extract the vendored copy from JAR resources, and - only when
 * {@code amsha.rag.vec-auto-download=true} - download the pinned upstream
 * release asset, verifying its SHA-256 against a constant recorded from the
 * official {@code checksums.txt} before it is written anywhere.
 *
 * <p>Fires at priority 10 - before {@code RagSchemaInitializer} (priority 100)
 * to ensure vec0 is available before the first SQLite connection is opened.
 */
@ApplicationScoped
public class Vec0NativeLibraryInstaller {

    private static final Logger log = Logger.getLogger(Vec0NativeLibraryInstaller.class);

    private static final String RESOURCE_PREFIX = "/native/vec0/vec0-";

    private static final String ASSET_TEMPLATE = "sqlite-vec-%s-loadable-%s.tar.gz";

    private static final String RELEASE_URL_TEMPLATE = "https://github.com/asg017/sqlite-vec/releases/download/v%s/";

    /** Default vec0 version; overridable via amsha.rag.vec-version. */
    private static final String DEFAULT_VEC_VERSION = "0.1.9";

    /**
     * Built-in digests from the v0.1.9 release checksums.txt, keyed by
     * osArchKey(). Versions other than the built-in one are verified against
     * the release's own checksums.txt instead (fetched before use).
     */
    private static final Map<String, String> TARGET_SHA256_0_1_9 = Map.of(
            "linux-x86_64", "b959baa1d8dc88861b1edb337b8587178cdcb12d60b4998f9d10b6a82052d5d7",
            "macos-aarch64", "8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29",
            "macos-x86_64", "53ad76e400786515e2edcaed2f01271dda846316390b761fadbd2dcf56aa4713",
            "windows-x86_64", "51581189d52066b4dfc6631f6d7a3eab7dedc2260656ab09ca97ab3fb8165983");

    @ConfigProperty(name = "amsha.rag.vec-extension-path", defaultValue = "./lib/vec0")
    String configuredVecPath;

    @ConfigProperty(name = "amsha.rag.vec-auto-download", defaultValue = "false")
    boolean autoDownload;

    @ConfigProperty(name = "amsha.rag.vec-version", defaultValue = DEFAULT_VEC_VERSION)
    String configuredVersion;

    void onStart(@Observes @Priority(10) StartupEvent ev) {
        performInstallation(configuredVecPath, autoDownload, configuredVersion);
    }

    /**
     * Performs binary resolution. Can be called as a CDI observer or statically
     * from main().
     */
    public static void performInstallation(String configuredVecPath, boolean autoDownload, String version) {
        if (configuredVecPath == null) return;
        String vecVersion = normalizedVersion(version);

        Path target = Path.of(configuredVecPath + nativeExtension());
        if (Files.exists(target)) {
            log.infof("Vec0 binary already present at [%s] - skipping auto-install.", target);
            return;
        }

        // Opt-in download runs first so an externally configured version is
        // honored even when a different version is vendored in the JAR; on any
        // failure we fall back to the offline resource path.
        if (autoDownload && tryDownloadAndInstall(target, vecVersion)) return;

        if (tryExtractFromResources(target)) return;

        if (autoDownload) return;

        log.warnf(
                "Vec0 binary not found in JAR for platform [%s]. "
                        + "RAG vector search will degrade to FTS5-only. "
                        + "Manually place the binary at [%s] or enable "
                        + "amsha.rag.vec-auto-download=true.",
                osArchKey(), target);
    }

    /** Kept for backward compatibility with callers that do not pass the flag. */
    public static void performInstallation(String configuredVecPath) {
        performInstallation(configuredVecPath, false, DEFAULT_VEC_VERSION);
    }

    /** Kept for backward compatibility with callers that do not pass a version. */
    public static void performInstallation(String configuredVecPath, boolean autoDownload) {
        performInstallation(configuredVecPath, autoDownload, DEFAULT_VEC_VERSION);
    }

    private static String normalizedVersion(String version) {
        if (version == null || version.isBlank()) return DEFAULT_VEC_VERSION;
        String trimmed = version.trim();
        return trimmed.startsWith("v") ? trimmed.substring(1) : trimmed;
    }

    private static boolean tryExtractFromResources(Path target) {
        String resourceName = RESOURCE_PREFIX + osArchKey() + nativeExtension();
        log.infof("Vec0 auto-install: extracting [%s] from JAR resources.", resourceName);

        try (InputStream in = Vec0NativeLibraryInstaller.class.getResourceAsStream(resourceName)) {
            if (in == null) {
                return false;
            }
            atomicWrite(target, in.readAllBytes());
            log.infof("Vec0 binary installed to [%s].", target.toAbsolutePath());
            return true;
        } catch (IOException e) {
            log.warnf("Vec0 auto-install failed: %s. RAG will degrade to FTS5-only.", LogUtil.rootMessage(e));
            return true;
        }
    }

    private static boolean tryDownloadAndInstall(Path target, String vecVersion) {
        String osArch = osArchKey();
        if ("unknown".equals(osArch)) {
            log.warnf("Vec0 download unsupported for platform [%s]; degrading to FTS5-only.", osArch);
            return false;
        }
        try {
            String expectedSha = resolveExpectedSha256(vecVersion, osArch);
            byte[] archive = fetch(releaseAssetUri(vecVersion, osArch));
            verifySha256(archive, expectedSha);
            byte[] binary = extractVecBinary(archive, nativeExtension());
            if (binary == null) {
                log.warn("Vec0 download did not contain the expected entry; degrading.");
                return false;
            }
            atomicWrite(target, binary);
            log.infof("Vec0 %s downloaded, verified and installed to [%s].", vecVersion, target.toAbsolutePath());
            return true;
        } catch (Exception e) {
            log.warnf("Vec0 auto-download failed: %s. Falling back to bundled resource.", LogUtil.rootMessage(e));
            return false;
        }
    }

    /**
     * Returns the pinned digest for versions with built-in knowledge, otherwise
     * downloads and parses the release's own checksums.txt. Fail-closed: any
     * resolution problem throws.
     */
    private static String resolveExpectedSha256(String vecVersion, String osArch)
            throws IOException, InterruptedException {
        if (DEFAULT_VEC_VERSION.equals(vecVersion)) {
            String pinned = TARGET_SHA256_0_1_9.get(osArch);
            if (pinned != null) return pinned;
        }
        URI checksums = URI.create(RELEASE_URL_TEMPLATE.formatted(vecVersion) + "checksums.txt");
        String assetName = ASSET_TEMPLATE.formatted(vecVersion, osArch);
        for (String line : new String(fetch(checksums), StandardCharsets.US_ASCII).split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && assetName.equals(parts[0])) {
                return parts[1];
            }
        }
        throw new IOException("No checksum found in release checksums.txt for " + assetName);
    }

    private static URI releaseAssetUri(String vecVersion, String osArch) {
        return URI.create(RELEASE_URL_TEMPLATE.formatted(vecVersion) + ASSET_TEMPLATE.formatted(vecVersion, osArch));
    }

    private static byte[] fetch(URI uri) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "amsha-vec-installer")
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
    }

    /**
     * Extracts the vec0 entry matching {@code extension} from an uncompressed
     * tar byte array. Returns null when no such member exists.
     */
    static byte[] extractVecBinary(byte[] gzippedTar, String extension) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzippedTar))) {
            return readMatchingEntry(gzip, extension);
        }
    }

    private static byte[] readMatchingEntry(GZIPInputStream in, String extension) throws IOException {
        byte[] header = new byte[512];
        while (readFully(in, header)) {
            if (isZeroBlock(header)) return null;
            long size = parseOctal(header, 124, 12);
            char typeflag = (char) header[156];
            String name = trimmedString(header, 0, 100);
            boolean regular = typeflag == '0' || typeflag == '\0';
            if (regular && name.endsWith("vec0" + extension)) {
                if (size > Integer.MAX_VALUE) throw new IOException("Tar entry too large: " + name);
                return in.readNBytes((int) size);
            }
            skipEntry(in, size);
        }
        return null;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = in.readNBytes(buffer, 0, buffer.length);
        return read == buffer.length;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static long parseOctal(byte[] header, int offset, int length) {
        String octal = trimmedString(header, offset, length).trim();
        return octal.isEmpty() ? 0 : Long.parseLong(octal, 8);
    }

    private static String trimmedString(byte[] buffer, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && buffer[end] != 0) end++;
        return new String(buffer, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static void skipEntry(InputStream in, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        long total = size + padding;
        while (total > 0) {
            long skipped = in.skip(total);
            if (skipped <= 0) throw new IOException("Unexpected end of tar stream");
            total -= skipped;
        }
    }

    /** Compares SHA-256 of {@code data} against lowercase hex {@code expected}. */
    static void verifySha256(byte[] data, String expected) {
        if (expected == null) return;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest(data)) {
            hex.append(String.format("%02x", b));
        }
        if (!hex.toString().equals(expected)) {
            throw new SecurityException("Vec0 download failed SHA-256 verification");
        }
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "vec0-", ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Returns the OS+arch key used in the resource filename. */
    static String osArchKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        if (os.contains("linux")) return arch.contains("aarch64") ? "linux-aarch64" : "linux-x86_64";
        if (os.contains("mac")) return arch.contains("aarch64") ? "macos-aarch64" : "macos-x86_64";
        if (os.contains("windows")) return "windows-x86_64";
        return "unknown";
    }

    /** Returns the native library file extension for the current OS. */
    static String nativeExtension() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) return ".dll";
        if (os.contains("mac")) return ".dylib";
        return ".so";
    }
}
