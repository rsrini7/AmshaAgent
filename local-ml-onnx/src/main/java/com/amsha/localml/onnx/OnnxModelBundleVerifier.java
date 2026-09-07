package com.amsha.localml.onnx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Loads a local manifest and verifies its files before ONNX Runtime can consume them. */
public class OnnxModelBundleVerifier {

    private static final long MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final long MAX_CONFIG_FILE_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;
    private final long maxModelBytes;

    public OnnxModelBundleVerifier(ObjectMapper objectMapper, long maxModelBytes) {
        this.objectMapper = objectMapper;
        if (maxModelBytes <= 0) throw new IllegalArgumentException("maxModelBytes must be positive");
        this.maxModelBytes = maxModelBytes;
    }

    public VerifiedOnnxModelBundle loadAndVerify(Path bundleDirectory) throws IOException {
        Path canonicalBundle = bundleDirectory.toRealPath();
        if (!Files.isDirectory(canonicalBundle)) {
            throw new IOException("Model bundle must be a directory");
        }
        Path manifestFile = canonicalBundle.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifestFile) > MAX_MANIFEST_BYTES) {
            throw new IOException("Bundle manifest must be a regular file no larger than one MiB");
        }
        OnnxModelBundleManifest manifest;
        try (InputStream manifestInput = Files.newInputStream(manifestFile)) {
            manifest = objectMapper.readValue(manifestInput, OnnxModelBundleManifest.class);
        }
        Path model =
                resolveAndVerify(canonicalBundle, manifest.modelFile(), manifest.modelSha256(), manifest.modelBytes());
        Path tokenizer = resolveAndVerify(
                canonicalBundle, manifest.tokenizerFile(), manifest.tokenizerSha256(), manifest.tokenizerBytes());
        Path decoder = null;
        Path preprocessorConfig = null;
        Path config = null;
        if (manifest.audioTranscription() != null) {
            OnnxAudioTranscriptionBundleSpec audioSpec = manifest.audioTranscription();
            decoder = resolveAndVerify(
                    canonicalBundle, audioSpec.decoderFile(), audioSpec.decoderSha256(), audioSpec.decoderBytes());
            if (audioSpec.preprocessorConfigFile() != null) {
                preprocessorConfig = resolveConfigFile(
                        canonicalBundle, audioSpec.preprocessorConfigFile(), audioSpec.preprocessorConfigSha256());
            }
            if (audioSpec.configFile() != null) {
                config = resolveConfigFile(canonicalBundle, audioSpec.configFile(), audioSpec.configSha256());
            }
        }
        return new VerifiedOnnxModelBundle(manifest, model, tokenizer, decoder, preprocessorConfig, config);
    }

    private Path resolveConfigFile(Path bundle, String relativeFile, String expectedSha256) throws IOException {
        Path file = bundle.resolve(relativeFile).normalize().toRealPath();
        if (!file.startsWith(bundle)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > MAX_CONFIG_FILE_BYTES) {
            throw new IOException(
                    "Bundle config file is not a valid regular file under bundle directory: " + relativeFile);
        }
        if (expectedSha256 != null) {
            byte[] actual = sha256Bytes(file);
            byte[] expected = HexFormat.of().parseHex(expectedSha256);
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new IOException("Bundle config file checksum verification failed: " + relativeFile);
            }
        }
        return file;
    }

    private Path resolveAndVerify(Path bundle, String relativeFile, String expectedSha256, long expectedBytes)
            throws IOException {
        Path file = bundle.resolve(relativeFile).normalize().toRealPath();
        if (!file.startsWith(bundle) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bundle file is not a regular file under the bundle directory: " + relativeFile);
        }
        long actualBytes = Files.size(file);
        if (actualBytes != expectedBytes || actualBytes > maxModelBytes) {
            throw new IOException("Bundle file size verification failed: " + relativeFile);
        }
        byte[] actual = sha256Bytes(file);
        byte[] expected = HexFormat.of().parseHex(expectedSha256);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new IOException("Bundle file checksum verification failed: " + relativeFile);
        }
        return file;
    }

    private static byte[] sha256Bytes(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
