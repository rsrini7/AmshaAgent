package com.amsha.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link FileSystemTaskRepository}: markdown CRUD, frontmatter parsing,
 * status/date queries, and name sanitization — all against a temp workspace.
 */
public class FileSystemTaskRepositoryTest {

    @TempDir
    Path tempDir;

    private FileSystemTaskRepository repo;

    @BeforeEach
    public void setUp() throws Exception {
        repo = new FileSystemTaskRepository();
        var field = FileSystemTaskRepository.class.getDeclaredField("workspaceRootDir");
        field.setAccessible(true);
        field.set(repo, tempDir.toString());
        var init = FileSystemTaskRepository.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(repo);
    }

    @Test
    void createWritesMarkdownWithFrontmatterAndSanitizesName() {
        TaskFile task = repo.create("Fix Login Bug!!", "steps to reproduce");

        assertTrue(task.id().endsWith(".md"));
        assertEquals(TaskState.TODO, task.status());
        // original display name preserved; sanitized slug lives in the filename
        assertEquals("Fix Login Bug!!", task.name());
        String fileName = Path.of(task.id()).getFileName().toString();
        assertTrue(fileName.matches("\\d{6}-fix-login-bug\\.md"));
        String content = read(task.id());
        assertTrue(content.contains("---"));
        assertTrue(content.contains("name: Fix Login Bug!!"));
        assertTrue(content.contains("status: todo"));
        assertTrue(content.contains("steps to reproduce"));
    }

    @Test
    void sanitizeNameStripsAndTruncates() {
        TaskFile longName =
                repo.create("This Is A Very Long Task Name That Definitely Exceeds Forty Characters Limit", "");
        String fileName = Path.of(longName.id()).getFileName().toString();
        // HHmmss- prefix (7 chars incl dash) + sanitized slug, whole suffix <= 40 chars
        String slug = fileName.substring("000000-".length()).replace(".md", "");
        assertFalse(slug.contains(" "));
        assertTrue(slug.length() <= 40);
        assertTrue(slug.matches("[a-z0-9-]+"));
    }

    @Test
    void findByIdRoundTripsPersistedState() {
        TaskFile created = repo.create("round trip", "body text");
        var parsed = repo.findById(created.id());

        assertTrue(parsed.isPresent());
        assertEquals("round trip", parsed.get().name());
        assertEquals(TaskState.TODO, parsed.get().status());

        assertTrue(repo.findById(tempDir.resolve("nope.md").toString()).isEmpty());
    }

    @Test
    void savePersistsStatusTransitionAndFindByDateReturnsIt() {
        TaskFile created = repo.create("status flow", "body");
        TaskFile updated = new TaskFile(
                created.id(),
                created.name(),
                TaskState.COMPLETED,
                created.createdAt(),
                created.updatedAt(),
                created.body());
        repo.save(updated);

        List<TaskFile> todays = repo.findByDate(LocalDate.now());
        assertEquals(1, todays.size());
        assertEquals(TaskState.COMPLETED, todays.getFirst().status());
    }

    @Test
    void findByStatusWalksTreeButSkipsRecurringDirectory() {
        repo.create("alpha", "");
        repo.create("beta done", "");
        TaskFile done = repo.listAll().stream()
                .filter(t -> t.name().contains("beta"))
                .findFirst()
                .orElseThrow();
        repo.save(new TaskFile(done.id(), done.name(), TaskState.COMPLETED, done.createdAt(), done.updatedAt(), ""));

        // recurring/ is excluded from walks
        Path recurring = tempDir.resolve("tasks/recurring/daily-job.md");
        writeRaw(recurring, "---\nid: rec-1\nname: daily-job\nstatus: TODO\n---\nrecurring body");

        List<TaskFile> todos = repo.findByStatus(TaskState.TODO);
        assertEquals(1, todos.size());
        assertTrue(todos.getFirst().name().contains("alpha"));

        assertEquals(2, repo.listAll().size());
    }

    @Test
    void parseContentToleratesMalformedFiles() {
        // No frontmatter -> empty
        assertTrue(FileSystemTaskRepository.parseContent(Path.of("x.md"), "just prose")
                .isEmpty());
        // Garbage timestamps fall back to now instead of throwing
        var parsed = FileSystemTaskRepository.parseContent(
                Path.of("123456-some-task.md"),
                "---\nstatus: bogus\ncreatedAt: not-a-date\nupdatedAt: also-bad\n---\nbody here");
        assertTrue(parsed.isPresent());
        assertEquals(TaskState.TODO, parsed.get().status());
        assertEquals("some task", parsed.get().name());
    }

    @Test
    void deleteRemovesTheUnderlyingFile() {
        TaskFile created = repo.create("delete me", "");
        assertTrue(repo.delete(created.id()));
        assertFalse(repo.delete(created.id()), "second delete of same id is false");
    }

    private String read(String id) {
        try {
            return Files.readString(Path.of(id));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeRaw(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
