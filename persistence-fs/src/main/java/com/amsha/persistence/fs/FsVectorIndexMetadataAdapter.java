package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.amsha.common.util.LogUtil;
import com.amsha.persistence.api.EmbeddingIndexDescriptor;
import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.VectorIndexCompatibility;
import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

/** Checksummed filesystem manifest for the Lucene vector index. */
@ApplicationScoped
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsVectorIndexMetadataAdapter implements VectorIndexMetadataPort {

    private static final Logger log = Logger.getLogger(FsVectorIndexMetadataAdapter.class);
    private static final int MANIFEST_VERSION = 1;
    private static final String MANIFEST_FILE = "index_descriptor.json";

    @Inject
    FsStoreProperties properties;

    @Inject
    ObjectMapper objectMapper;

    private final Map<VectorIndexNamespace, VectorIndexCompatibility> compatibility = new ConcurrentHashMap<>();

    @Override
    public VectorIndexCompatibility verifyOrInitialize(
            VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor, boolean indexEmpty) {
        try {
            Optional<EmbeddingIndexDescriptor> stored = find(namespace);
            VectorIndexCompatibility result =
                    VectorIndexCompatibility.assess(namespace, activeDescriptor, stored, indexEmpty);
            if (result.status() == VectorIndexCompatibility.Status.INITIALIZED) {
                writeManifest(namespace, activeDescriptor);
            }
            compatibility.put(namespace, result);
            logResult(result);
            return result;
        } catch (RuntimeException e) {
            VectorIndexCompatibility result = unavailable(namespace, activeDescriptor);
            compatibility.put(namespace, result);
            log.warnf(
                    "Filesystem vector namespace [%s] manifest unavailable; vector search disabled: %s",
                    namespace.persistentId(), LogUtil.rootMessage(e));
            return result;
        }
    }

    @Override
    public VectorIndexCompatibility markUnavailable(VectorIndexNamespace namespace) {
        VectorIndexCompatibility result = VectorIndexCompatibility.unavailable(namespace);
        compatibility.put(namespace, result);
        return result;
    }

    @Override
    public VectorIndexCompatibility markUnavailable(
            VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor) {
        VectorIndexCompatibility result = unavailable(namespace, activeDescriptor);
        compatibility.put(namespace, result);
        return result;
    }

    @Override
    public Optional<EmbeddingIndexDescriptor> find(VectorIndexNamespace namespace) {
        Path manifestPath = manifestPath(namespace);
        if (!Files.isRegularFile(manifestPath)) return Optional.empty();
        try {
            IndexDescriptorManifest manifest =
                    objectMapper.readValue(manifestPath.toFile(), IndexDescriptorManifest.class);
            if (manifest.manifestVersion() != MANIFEST_VERSION
                    || !namespace.persistentId().equals(manifest.namespace())
                    || !manifest.checksum().equals(checksum(manifest.namespace(), manifest.descriptor()))) {
                throw new IllegalStateException("Filesystem embedding-index manifest checksum or identity is invalid");
            }
            return Optional.of(manifest.descriptor());
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read filesystem embedding-index manifest", e);
        }
    }

    @Override
    public boolean isVectorEnabled(VectorIndexNamespace namespace) {
        VectorIndexCompatibility result = compatibility.get(namespace);
        return result != null && result.vectorEnabled();
    }

    @Override
    public Map<VectorIndexNamespace, VectorIndexCompatibility> compatibilitySnapshot() {
        return Map.copyOf(compatibility);
    }

    private void writeManifest(VectorIndexNamespace namespace, EmbeddingIndexDescriptor descriptor) {
        Path manifestPath = manifestPath(namespace);
        Path parent = manifestPath.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".index_descriptor.", ".tmp");
            IndexDescriptorManifest manifest = new IndexDescriptorManifest(
                    MANIFEST_VERSION,
                    namespace.persistentId(),
                    descriptor,
                    checksum(namespace.persistentId(), descriptor));
            objectMapper.writeValue(temporary.toFile(), manifest);
            try {
                Files.move(
                        temporary, manifestPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write filesystem embedding-index manifest", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.debugf("Unable to delete temporary embedding-index manifest: %s", LogUtil.rootMessage(e));
                }
            }
        }
    }

    private Path manifestPath(VectorIndexNamespace namespace) {
        Path dataRoot = Path.of(properties.dataDir()).toAbsolutePath().normalize();
        Path namespaceRoot;
        if (namespace == VectorIndexNamespace.RAG_GLOBAL) {
            Path configuredIndex = Path.of(properties.luceneIndexDir());
            namespaceRoot = configuredIndex.isAbsolute()
                    ? configuredIndex.normalize()
                    : dataRoot.resolve(configuredIndex).normalize();
        } else {
            namespaceRoot = dataRoot.resolve("vector-indexes")
                    .resolve(namespace.persistentId())
                    .normalize();
        }
        if (!namespaceRoot.startsWith(dataRoot)
                && !Path.of(properties.luceneIndexDir()).isAbsolute()) {
            throw new IllegalStateException("Filesystem vector-index manifest path escapes the configured data root");
        }
        return namespaceRoot.resolve(MANIFEST_FILE);
    }

    private static String checksum(String namespace, EmbeddingIndexDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        String canonical = MANIFEST_VERSION + "\n" + namespace + "\n" + descriptor.fingerprint() + "\n"
                + descriptor.vectorDimension();
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void logResult(VectorIndexCompatibility result) {
        if (result.vectorEnabled()) {
            log.infof(
                    "Filesystem vector namespace [%s] descriptor %s (%s)",
                    result.namespace().persistentId(), result.activeFingerprint(), result.status());
            return;
        }
        log.warnf(
                "Filesystem vector namespace [%s] descriptor %s; vector search disabled. "
                        + "Development reset: stop Amsha and delete the configured development data directory.",
                result.namespace().persistentId(), result.status());
    }

    private static VectorIndexCompatibility unavailable(
            VectorIndexNamespace namespace, EmbeddingIndexDescriptor activeDescriptor) {
        return new VectorIndexCompatibility(
                namespace,
                VectorIndexCompatibility.Status.UNAVAILABLE,
                activeDescriptor.fingerprint(),
                Optional.empty());
    }

    private record IndexDescriptorManifest(
            int manifestVersion, String namespace, EmbeddingIndexDescriptor descriptor, String checksum) {}
}
