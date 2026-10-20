package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Branch-level coverage for {@link Vec0NativeLibraryInstaller}: the platform-key matrix, the
 * resource/download fallback ladder, the best-effort write path, and the tar/HTTP helpers.
 *
 * <p>The download path is exercised through its real seams only. {@code RELEASE_URL_TEMPLATE} is a
 * hard-coded constant with no injection point, so the two genuinely remote steps - fetching the
 * release {@code checksums.txt} and fetching the asset tarball - are reached with a deliberately
 * invalid version (no network at all) rather than by contacting github.
 */
class Vec0NativeLibraryInstallerBranchTest {

    private static final String PINNED_MACOS_AARCH64 =
            "8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29";

    @Test
    void osArchKeyAndExtensionCoverWindowsAndUnknownPlatforms() {
        assertEquals("windows-x86_64", withOs("Windows 11", "amd64", Vec0NativeLibraryInstaller::osArchKey));
        assertEquals(".dll", withOs("Windows 11", "amd64", Vec0NativeLibraryInstaller::nativeExtension));

        assertEquals("unknown", withOs("Plan 9", "mips", Vec0NativeLibraryInstaller::osArchKey));
        assertEquals(".so", withOs("Plan 9", "mips", Vec0NativeLibraryInstaller::nativeExtension));
    }

    @Test
    void missingVendoredBinaryWithoutAutoDownloadDegradesToFtsOnly(@TempDir Path tempDir) {
        String base = tempDir.resolve("lib").resolve("vec0").toString();

        // An unmapped platform has no vendored resource and no download permission, so the installer
        // must warn and leave the target absent rather than failing the boot.
        withOs("Plan 9", "mips", () -> {
            Vec0NativeLibraryInstaller.performInstallation(base, false, "0.1.9");
            return null;
        });

        assertFalse(Files.exists(tempDir.resolve("lib").resolve("vec0.so")));
    }

    @Test
    void missingVendoredBinaryWithAutoDownloadAttemptsTheDownloadFirst(@TempDir Path tempDir) {
        String base = tempDir.resolve("lib").resolve("vec0").toString();

        // autoDownload=true, but the platform is unsupported: the download is skipped and the
        // resource miss must NOT fall through to the "not found in JAR" warning.
        withOs("Plan 9", "mips", () -> {
            Vec0NativeLibraryInstaller.performInstallation(base, true, "0.1.9");
            return null;
        });

        assertFalse(Files.exists(tempDir.resolve("lib").resolve("vec0.so")));
    }

    @Test
    void twoArgOverloadUsesTheBuiltInVersion(@TempDir Path tempDir) throws IOException {
        String base = tempDir.resolve("lib").resolve("vec0").toString();
        Vec0NativeLibraryInstaller.performInstallation(base, false);

        Path installed = Path.of(base + Vec0NativeLibraryInstaller.nativeExtension());
        assertTrue(Files.exists(installed), "the two-arg overload must install the bundled binary");
        assertTrue(Files.readAllBytes(installed).length > 1000);
    }

    @Test
    void extractionWriteFailureIsSwallowedAsBestEffort(@TempDir Path tempDir) throws IOException {
        // The target's parent is an ordinary file, so createDirectories fails inside atomicWrite.
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        String base = blocker.resolve("vec0").toString();

        // Must not propagate: a failed install degrades to FTS5-only rather than aborting startup.
        Vec0NativeLibraryInstaller.performInstallation(base, false, "0.1.9");

        assertEquals("not a directory", Files.readString(blocker));
    }

    @Test
    void downloadFailureFallsBackToTheBundledBinary(@TempDir Path tempDir) {
        String base = tempDir.resolve("lib").resolve("vec0").toString();

        // Pinned platform: the download must be attempted (so a legal arch key is required) and the
        // vendored macos-aarch64 binary must be the fallback. A version containing a space cannot
        // form a legal release URI, so the download fails before any socket is opened.
        withOs("Mac OS X", "aarch64", () -> {
            assertEquals("macos-aarch64", Vec0NativeLibraryInstaller.osArchKey());
            Vec0NativeLibraryInstaller.performInstallation(base, true, "1 0");
            return null;
        });

        assertTrue(
                Files.exists(tempDir.resolve("lib").resolve("vec0.dylib")),
                "a failed download must fall back to the JAR resource");
    }

    @Test
    void unresolvableVersionFailsBeforeAnySocketIsOpened() throws Exception {
        // Exercises the checksums.txt resolution path without contacting github: a space is illegal
        // in a URI, so the failure is raised while the release URI is built.
        Throwable failure = invokeExpectingFailure(
                "resolveExpectedSha256", new Class<?>[] {String.class, String.class}, "1 0", "macos-aarch64");
        assertTrue(failure instanceof IllegalArgumentException, "expected IllegalArgumentException, got " + failure);
    }

    @Test
    void directDownloadAttemptFailsClosedWithoutWritingAnything(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("lib").resolve("vec0.dylib");
        Object outcome = withOs("Mac OS X", "aarch64", () -> {
            try {
                return invokeStatic("tryDownloadAndInstall", new Class<?>[] {Path.class, String.class}, target, "1 0");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertEquals(Boolean.FALSE, outcome);
        assertFalse(Files.exists(target));
    }

    @Test
    void pinnedDigestIsPreferredForTheBuiltInVersion() throws Exception {
        Object digest = invokeStatic(
                "resolveExpectedSha256", new Class<?>[] {String.class, String.class}, "0.1.9", "macos-aarch64");
        assertEquals(PINNED_MACOS_AARCH64, digest);
    }

    @Test
    void releaseAssetUriPointsAtThePinnedUpstreamAsset() throws Exception {
        Object uri =
                invokeStatic("releaseAssetUri", new Class<?>[] {String.class, String.class}, "0.1.9", "macos-aarch64");
        assertEquals(
                "https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/"
                        + "sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz",
                uri.toString());
    }

    @Test
    void fetchRejectsANonSuccessStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/missing");
            Throwable failure = invokeExpectingFailure("fetch", new Class<?>[] {URI.class}, uri);
            assertTrue(failure instanceof IOException, "expected IOException, got " + failure);
            assertTrue(failure.getMessage().contains("HTTP 404"), failure.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetchReturnsTheBodyOnSuccess() throws Exception {
        byte[] payload = "checksums".getBytes(StandardCharsets.US_ASCII);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (var body = exchange.getResponseBody()) {
                body.write(payload);
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ok");
            assertArrayEquals(payload, (byte[]) invokeStatic("fetch", new Class<?>[] {URI.class}, uri));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsNullWhenTheArchiveEndsWithoutAMatchOrZeroBlock() throws IOException {
        // No end-of-archive zero block: the scan must terminate because the stream ran dry.
        byte[] tar = tarWithoutEndBlocks("README", "docs".getBytes(StandardCharsets.UTF_8));
        assertNull(Vec0NativeLibraryInstaller.extractVecBinary(gzip(tar), ".so"));
    }

    // --- helpers -----------------------------------------------------------------------------

    private static <T> T withOs(String osName, String arch, Supplier<T> action) {
        String originalName = System.getProperty("os.name");
        String originalArch = System.getProperty("os.arch");
        try {
            System.setProperty("os.name", osName);
            System.setProperty("os.arch", arch);
            return action.get();
        } finally {
            restoreProperty("os.name", originalName);
            restoreProperty("os.arch", originalArch);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = Vec0NativeLibraryInstaller.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new AssertionError(name + " threw unexpectedly", e.getCause());
        }
    }

    private static Throwable invokeExpectingFailure(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = Vec0NativeLibraryInstaller.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            Object result = method.invoke(null, args);
            throw new AssertionError(name + " should have failed but returned " + result);
        } catch (InvocationTargetException e) {
            return e.getCause();
        }
    }

    /** Minimal ustar header + data + padding, deliberately WITHOUT the trailing zero blocks. */
    private static byte[] tarWithoutEndBlocks(String name, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeOctal(header, 124, content.length, 12);
        header[156] = '0';
        writeOctal(header, 148, checksumOf(header), 8);
        out.writeBytes(header);
        out.writeBytes(content);
        out.writeBytes(new byte[(512 - (content.length % 512)) % 512]);
        return out.toByteArray();
    }

    private static void writeOctal(byte[] header, int offset, long value, int length) {
        String octal = Long.toOctalString(value);
        String padded = "0".repeat(length - 1 - octal.length()) + octal + "\0";
        System.arraycopy(padded.getBytes(StandardCharsets.US_ASCII), 0, header, offset, length);
    }

    /** The tar checksum is the byte sum of the header with its checksum field treated as spaces. */
    private static long checksumOf(byte[] header) {
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            boolean checksumField = i >= 148 && i < 156;
            sum += checksumField ? ' ' : (header[i] & 0xFF);
        }
        return sum;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}
