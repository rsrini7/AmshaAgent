package com.arcclaw.core.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Persists agent tasks as Markdown files with YAML frontmatter.
 * <p>
 * Directory layout:
 * <pre>
 * {workspace}/tasks/
 *   yyyy-MM-dd/
 *     HHmmss-{name}.md
 *   recurring/
 *     {name}.md
 * </pre>
 */
@ApplicationScoped
public class FileSystemTaskRepository {

    private static final Logger LOG = Logger.getLogger(FileSystemTaskRepository.class);

    private static final Pattern FRONTMATTER_PATTERN =
        Pattern.compile("^---\\s*\n(.*?)\\n---\\s*\n(.*)?", Pattern.DOTALL);

    private static final Pattern FRONTMATTER_LINE =
        Pattern.compile("^(\\w+):\\s*(.*)\\s*$", Pattern.MULTILINE);

    private static final Pattern FILENAME_PATTERN =
        Pattern.compile("^(\\d{6})-(.+)\\.md$");

    @Inject
    @ConfigProperty(name = "arcclaw.workspace.root-dir", defaultValue = "./data/workspaces")
    String workspaceRootDir;

    private Path tasksRoot;

    @PostConstruct
    void init() {
        this.tasksRoot = Path.of(workspaceRootDir).resolve("tasks");
        try {
            Files.createDirectories(tasksRoot);
            Files.createDirectories(tasksRoot.resolve("recurring"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create tasks directory: " + tasksRoot, e);
        }
    }

    // ---------------------------------------------------------------
    // CRUD
    // ---------------------------------------------------------------

    public TaskFile create(String name, String body) {
        String safeName = sanitizeName(name);
        LocalDate today = LocalDate.now();
        Path dateDir = tasksRoot.resolve(today.format(DateTimeFormatter.ISO_LOCAL_DATE));
        try {
            Files.createDirectories(dateDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create date directory: " + dateDir, e);
        }

        String timestamp = DateTimeFormatter.ofPattern("HHmmss").format(LocalTime.now());
        String filename = timestamp + "-" + safeName + ".md";
        Path file = dateDir.resolve(filename);

        TaskFile task = new TaskFile(
            file.toAbsolutePath().toString(),
            name,
            TaskState.TODO,
            Instant.now(),
            Instant.now(),
            body
        );
        writeFile(file, task.toFileContent());
        LOG.infof("Created task file: %s", file);
        return task;
    }

    public Optional<TaskFile> findById(String id) {
        Path path = Path.of(id);
        if (!Files.exists(path)) return Optional.empty();
        return parseFile(path);
    }

    public List<TaskFile> findByDate(LocalDate date) {
        Path dateDir = tasksRoot.resolve(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        if (!Files.exists(dateDir)) return List.of();
        return listFiles(dateDir);
    }

    public List<TaskFile> findByStatus(TaskState status) {
        List<TaskFile> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(tasksRoot, 3)) {
            paths.filter(p -> p.toString().endsWith(".md"))
                 .filter(p -> !p.startsWith(tasksRoot.resolve("recurring").toString()))
                 .forEach(p -> parseFile(p).ifPresent(t -> {
                     if (t.status() == status) result.add(t);
                 }));
        } catch (IOException e) {
            LOG.warnf("Failed to walk tasks directory: %s", e.getMessage());
        }
        return result;
    }

    public List<TaskFile> listAll() {
        List<TaskFile> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(tasksRoot, 3)) {
            paths.filter(p -> p.toString().endsWith(".md"))
                 .filter(p -> !p.startsWith(tasksRoot.resolve("recurring").toString()))
                 .forEach(p -> parseFile(p).ifPresent(result::add));
        } catch (IOException e) {
            LOG.warnf("Failed to walk tasks directory: %s", e.getMessage());
        }
        return result;
    }

    public TaskFile save(TaskFile task) {
        Path path = Path.of(task.id());
        writeFile(path, task.toFileContent());
        return task;
    }

    public boolean delete(String id) {
        try {
            return Files.deleteIfExists(Path.of(id));
        } catch (IOException e) {
            LOG.warnf("Failed to delete task %s: %s", id, e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------

    private List<TaskFile> listFiles(Path dir) {
        List<TaskFile> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .sorted()
                 .forEach(p -> parseFile(p).ifPresent(result::add));
        } catch (IOException e) {
            LOG.warnf("Failed to list tasks in %s: %s", dir, e.getMessage());
        }
        return result;
    }

    private Optional<TaskFile> parseFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parseContent(path, content);
        } catch (IOException e) {
            LOG.warnf("Failed to read task file %s: %s", path, e.getMessage());
            return Optional.empty();
        }
    }

    static Optional<TaskFile> parseContent(Path path, String content) {
        Matcher fm = FRONTMATTER_PATTERN.matcher(content);
        if (!fm.matches()) return Optional.empty();

        String headerBlock = fm.group(1);
        String body = fm.group(2) != null ? fm.group(2).trim() : "";

        Matcher lm = FRONTMATTER_LINE.matcher(headerBlock);
        String id = path.toAbsolutePath().toString();
        String name = path.getFileName().toString().replace(".md", "");
        TaskState status = TaskState.TODO;
        Instant createdAt = Instant.now();
        Instant updatedAt = Instant.now();

        while (lm.find()) {
            switch (lm.group(1)) {
                case "id" -> id = lm.group(2);
                case "name" -> name = lm.group(2);
                case "status" -> status = TaskState.fromString(lm.group(2));
                case "createdAt" -> createdAt = safeParseInstant(lm.group(2));
                case "updatedAt" -> updatedAt = safeParseInstant(lm.group(2));
            }
        }

        // Derive a shorter display name from the filename pattern HHmmss-slug.md
        Matcher fn = FILENAME_PATTERN.matcher(path.getFileName().toString());
        if (fn.matches()) {
            name = fn.group(2).replace("-", " ").replace("_", " ");
        }

        return Optional.of(new TaskFile(id, name, status, createdAt, updatedAt, body));
    }

    private static void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write task file: " + path, e);
        }
    }

    private static String sanitizeName(String name) {
        String safe = name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
        if (safe.length() > 40) safe = safe.substring(0, 40);
        return safe;
    }

    private static Instant safeParseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
