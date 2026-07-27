package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillAdaptationManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void marksSkillsAndListsOnlyAdaptableFiles() throws Exception {
        SkillAdaptationManager manager = new SkillAdaptationManager();
        manager.adaptiveEnabled = true;
        Files.writeString(tempDir.resolve("SKILL.md"), "skill");
        Files.writeString(tempDir.resolve("run.sh"), "script");
        Files.writeString(tempDir.resolve("ignored.txt"), "ignored");

        assertFalse(manager.isAdapted(tempDir.toString()));
        assertTrue(manager.getAdaptationInstructions(tempDir.toString()).contains("First Run"));
        assertEquals(2, manager.getAdaptableFiles(tempDir.toString()).size());

        manager.markAdapted(tempDir.toString());
        assertTrue(manager.isAdapted(tempDir.toString()));
        assertTrue(manager.getAdaptationInstructions(tempDir.toString()).contains("already been adapted"));
    }

    @Test
    void handlesDisabledInvalidAndUnwritableLocations() {
        SkillAdaptationManager manager = new SkillAdaptationManager();
        assertFalse(manager.isAdaptiveEnabled());
        assertEquals("", manager.getAdaptationInstructions("missing"));
        assertFalse(manager.isAdapted(null));
        assertFalse(manager.isAdapted(" "));
        assertTrue(manager.getAdaptableFiles(null).isEmpty());
        assertTrue(
                manager.getAdaptableFiles(tempDir.resolve("missing").toString()).isEmpty());
        assertDoesNotThrow(() -> manager.markAdapted(null));
        assertDoesNotThrow(
                () -> manager.markAdapted(tempDir.resolve("missing/child").toString()));
    }
}
