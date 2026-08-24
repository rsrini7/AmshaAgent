package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Vec0NativeLibraryInstallerTest {

    @Test
    void osArchKeyReturnsKnownValue() {
        String key = Vec0NativeLibraryInstaller.osArchKey();
        assertFalse("unknown".equals(key), "osArchKey should be known on standard CI/dev boxes");
    }

    @Test
    void nativeExtensionMatchesOs() {
        String ext = Vec0NativeLibraryInstaller.nativeExtension();
        assertTrue(
                ext.equals(".so") || ext.equals(".dll") || ext.equals(".dylib"),
                "ext must be one of .so, .dll, .dylib");
    }

    @Test
    void resourceExistsInJarForCurrentPlatform() throws IOException {
        String resourceName = "/native/vec0/vec0-" + Vec0NativeLibraryInstaller.osArchKey()
                + Vec0NativeLibraryInstaller.nativeExtension();
        try (InputStream in = Vec0NativeLibraryInstallerTest.class.getResourceAsStream(resourceName)) {
            assertNotNull(in, "Vec0 binary resource " + resourceName + " must exist in the JAR");
            byte[] binary = in.readAllBytes();
            assertTrue(binary.length > 1000, "vendored vec0 binary looks non-trivial");
            String asText = new String(binary, StandardCharsets.ISO_8859_1);
            assertTrue(asText.contains("v0.1.9"), "bundled binary must report v0.1.9");
        }
    }

    @Test
    void installerDoesNotOverwriteExistingBinary(@TempDir Path tempDir) throws IOException {
        // Create a sentinel file at the target path
        Path sentinelDir = tempDir.resolve("lib");
        Files.createDirectories(sentinelDir);
        Path sentinel = sentinelDir.resolve("vec0" + Vec0NativeLibraryInstaller.nativeExtension());
        Files.writeString(sentinel, "sentinel");

        Vec0NativeLibraryInstaller installer = new Vec0NativeLibraryInstaller();
        installer.configuredVecPath = sentinelDir.resolve("vec0").toString();
        installer.onStart(null); // must not overwrite

        assertEquals("sentinel", Files.readString(sentinel));
    }

    @Test
    void offlineDefaultInstallsFromBundledResources(@TempDir Path tempDir) throws IOException {
        // With auto-download disabled, resolution must still succeed purely
        // from the vendored JAR resource - no network involved.
        String base = tempDir.resolve("lib").resolve("vec0").toString();
        Vec0NativeLibraryInstaller.performInstallation(base, false, "0.1.9");

        Path installed = Path.of(base + Vec0NativeLibraryInstaller.nativeExtension());
        assertTrue(Files.exists(installed), "binary must be installed from JAR resources");
        byte[] bytes = Files.readAllBytes(installed);
        assertTrue(bytes.length > 1000, "installed binary looks non-trivial");
        assertTrue(
                new String(bytes, StandardCharsets.ISO_8859_1).contains("v0.1.9"),
                "installed binary must be the vendored v0.1.9");
    }

    @Test
    void assetNameFollowsUpstreamConvention() {
        assertEquals("sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz", upstreamAssetName("0.1.9", "macos-aarch64"));
    }

    private static String upstreamAssetName(String version, String osArch) {
        return "sqlite-vec-" + version + "-loadable-" + osArch + ".tar.gz";
    }

    @Test
    void verifySha256AcceptsCorrectAndRejectsWrongDigest() {
        byte[] data = "amsha".getBytes(StandardCharsets.UTF_8);
        String digestHex = "bb5eb24c11682749ea9481455386f3773bff963e24d857431798ebecd42e69d2";
        Vec0NativeLibraryInstaller.verifySha256(data, digestHex);
        assertThrows(
                SecurityException.class,
                () -> Vec0NativeLibraryInstaller.verifySha256(data, "00" + digestHex.substring(2)));
    }

    @Test
    void extractsVecEntryFromSyntheticTarGz() throws IOException {
        String extension = Vec0NativeLibraryInstaller.nativeExtension();
        byte[] tar = syntheticTarWithEntries(
                new String[] {"README", "vec0" + extension},
                new byte[][] {"docs".getBytes(StandardCharsets.UTF_8), "BINARY".getBytes(StandardCharsets.UTF_8)});
        byte[] gzipped = gzip(tar);

        byte[] extracted = Vec0NativeLibraryInstaller.extractVecBinary(gzipped, extension);
        assertArrayEquals("BINARY".getBytes(StandardCharsets.UTF_8), extracted);
    }

    @Test
    void returnsNullWhenArchiveHasNoVecEntry() throws IOException {
        byte[] tar = syntheticTarWithEntries(
                new String[] {"README"}, new byte[][] {"docs".getBytes(StandardCharsets.UTF_8)});
        assertNull(Vec0NativeLibraryInstaller.extractVecBinary(gzip(tar), ".so"));
    }

    /** Builds a minimal ustar archive: 512-byte header + data + padding per entry. */
    private static byte[] syntheticTarWithEntries(String[] names, byte[][] contents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < names.length; i++) {
            byte[] header = new byte[512];
            byte[] nameBytes = names[i].getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
            writeOctal(header, 124, contents[i].length, 12);
            writeOctal(header, 136, 0, 8); // uid
            writeOctal(header, 148, 0, 8); // checksum placeholder
            header[156] = '0';
            writeOctal(header, 148, checksumOf(header), 8); // real checksum
            out.writeBytes(header);
            out.writeBytes(contents[i]);
            int padding = (512 - (contents[i].length % 512)) % 512;
            out.writeBytes(new byte[padding]);
        }
        out.writeBytes(new byte[1024]); // end-of-archive blocks
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
