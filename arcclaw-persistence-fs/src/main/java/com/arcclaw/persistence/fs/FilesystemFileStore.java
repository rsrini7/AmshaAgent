package com.arcclaw.persistence.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "arcclaw.persistence.backend", stringValue = "fs")
public class FilesystemFileStore {

    private final Path dataDir;
    private final ObjectMapper objectMapper;

    @Inject
    public FilesystemFileStore(FsStoreProperties properties) {
        this.dataDir = Path.of(properties.dataDir()).toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    protected FilesystemFileStore() {
        this.dataDir = Path.of("./data/fs").toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);
    }

    @PostConstruct
    void ensureDataDir() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create data directory: " + dataDir, e);
        }
    }

    public Path resolve(List<String> segments) {
        Path result = dataDir;
        for (String segment : segments) {
            result = result.resolve(sanitize(segment));
        }
        return result;
    }

    public <T> void write(List<String> segments, T value) {
        Path target = resolve(segments);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), value);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write: " + target, e);
        }
    }

    public <T> T read(List<String> segments, Class<T> type) {
        Path target = resolve(segments);
        try {
            return objectMapper.readValue(target.toFile(), type);
        } catch (NoSuchFileException e) {
            throw new UncheckedIOException("File not found: " + target, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read: " + target, e);
        }
    }

    public <T> Optional<T> readOptional(List<String> segments, Class<T> type) {
        Path target = resolve(segments);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), type));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read: " + target, e);
        }
    }

    public boolean exists(List<String> segments) {
        return Files.exists(resolve(segments));
    }

    public boolean delete(List<String> segments) {
        try {
            return Files.deleteIfExists(resolve(segments));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete: " + resolve(segments), e);
        }
    }

    public void deleteDirectory(List<String> segments) {
        Path dir = resolve(segments);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete directory: " + dir, e);
        }
    }

    public List<String> list(List<String> segments, String suffix) {
        Path dir = resolve(segments);
        if (!Files.exists(dir)) return List.of();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(suffix)) {
                    names.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list: " + dir, e);
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    public List<String> listDirs(List<String> segments) {
        Path dir = resolve(segments);
        if (!Files.exists(dir)) return List.of();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list dirs: " + dir, e);
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    private static String sanitize(String segment) {
        if (segment == null || segment.isEmpty()) return "_";
        return segment.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
