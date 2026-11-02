package com.amsha.persistence.fs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.SchemaContract;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.jboss.logging.Logger;

import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Manages the versioned schema contract manifest for filesystem persistence.
 *
 * <p>Under the reset-only rollout (ADR-Tool-Attempt-Settlement-Reset-Only-V1-Baseline),
 * an empty data root may be initialized atomically with the current contract marker.
 * A non-empty data root with no marker, a malformed marker, or an unsupported contract
 * version fails closed without modifying or repairing the filesystem.
 */
@ApplicationScoped
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.FILESYSTEM)
public class FsSchemaContractManager {

    private static final Logger log = Logger.getLogger(FsSchemaContractManager.class);

    public static final String CONTRACT_NAME = SchemaContract.CONTRACT_NAME;
    public static final int CONTRACT_VERSION = SchemaContract.CONTRACT_VERSION;
    public static final String MANIFEST_FILE_NAME = SchemaContract.SCHEMA_CONTRACT_FILE;

    private final Path dataDir;
    private final ObjectMapper objectMapper;

    @Inject
    public FsSchemaContractManager(FsStoreProperties properties) {
        this(Path.of(properties.dataDir()).toAbsolutePath().normalize());
    }

    public FsSchemaContractManager(Path dataDir) {
        this.dataDir = dataDir;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    public Path dataDir() {
        return dataDir;
    }

    /**
     * Verifies the schema contract manifest in the data directory.
     *
     * <p>If the data root is empty, initializes the manifest atomically.
     * If the data root is non-empty and missing the manifest, malformed, or has an
     * unsupported version, throws {@link IllegalStateException}.
     */
    public void verifyOrInitialize() {
        Path manifestPath = dataDir.resolve(MANIFEST_FILE_NAME);
        if (Files.exists(manifestPath)) {
            verifyExistingManifest(manifestPath);
            return;
        }

        if (isDataRootEmpty()) {
            initializeManifestAtomically(manifestPath);
            return;
        }

        throw new IllegalStateException("Filesystem persistence root '" + dataDir
                + "' is non-empty but missing " + MANIFEST_FILE_NAME
                + ". Refusing startup against un-migrated or un-reset filesystem data.");
    }

    private void verifyExistingManifest(Path manifestPath) {
        SchemaContractManifest manifest;
        try {
            manifest = objectMapper.readValue(manifestPath.toFile(), SchemaContractManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Filesystem persistence schema-contract manifest at '" + manifestPath + "' is malformed.", e);
        }

        if (manifest == null || manifest.contractName() == null) {
            throw new IllegalStateException("Filesystem persistence schema-contract manifest at '" + manifestPath
                    + "' is missing contract name.");
        }

        if (!CONTRACT_NAME.equals(manifest.contractName())) {
            throw new IllegalStateException("Filesystem persistence schema-contract mismatch: expected contract '"
                    + CONTRACT_NAME + "', but found '" + manifest.contractName() + "'.");
        }

        if (manifest.contractVersion() != CONTRACT_VERSION) {
            throw new IllegalStateException("Filesystem persistence schema-contract version mismatch: expected version "
                    + CONTRACT_VERSION + ", but found version " + manifest.contractVersion() + ".");
        }

        log.infof("Filesystem persistence schema-contract verified: '%s' (v%d).", CONTRACT_NAME, CONTRACT_VERSION);
    }

    private boolean isDataRootEmpty() {
        if (!Files.exists(dataDir)) {
            return true;
        }
        try (Stream<Path> stream = Files.list(dataDir)) {
            List<Path> entries = stream.toList();
            if (entries.isEmpty()) {
                return true;
            }
            boolean hasOtherFiles = false;
            for (Path entry : entries) {
                String fileName = entry.getFileName().toString();
                if (isManagerTempFile(fileName)) {
                    // Stale temp file from interrupted manifest creation: clean it up safely
                    try {
                        Files.deleteIfExists(entry);
                        log.infof("Cleaned up interrupted schema-contract temp file: %s", entry);
                    } catch (IOException e) {
                        log.warnf("Could not delete interrupted temp file %s: %s", entry, e.getMessage());
                    }
                    continue;
                }
                if (fileName.equals("locks") && Files.isDirectory(entry)) {
                    try (Stream<Path> lockStream = Files.list(entry)) {
                        if (lockStream.findAny().isPresent()) {
                            hasOtherFiles = true;
                        }
                    }
                    continue;
                }
                if (fileName.equals(".DS_Store")) {
                    continue;
                }
                hasOtherFiles = true;
            }
            return !hasOtherFiles;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect filesystem data root: " + dataDir, e);
        }
    }

    private static boolean isManagerTempFile(String fileName) {
        return fileName.startsWith("schema-contract") && fileName.endsWith(".tmp");
    }

    private void initializeManifestAtomically(Path manifestPath) {
        try {
            Files.createDirectories(dataDir);
            SchemaContractManifest manifest = new SchemaContractManifest(
                    CONTRACT_NAME, CONTRACT_VERSION, Instant.now().toString());

            Path tempFile = Files.createTempFile(dataDir, "schema-contract", ".tmp");
            objectMapper.writeValue(tempFile.toFile(), manifest);
            Files.move(tempFile, manifestPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.infof("Filesystem persistence schema-contract initialized atomically at '%s'.", manifestPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize schema-contract manifest at " + manifestPath, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SchemaContractManifest(
            @JsonProperty("contract_name") @JsonAlias("contractName")
            String contractName,

            @JsonProperty("contract_version") @JsonAlias("contractVersion")
            int contractVersion,

            @JsonProperty("applied_at") @JsonAlias("appliedAt")
            String appliedAt) {}
}
