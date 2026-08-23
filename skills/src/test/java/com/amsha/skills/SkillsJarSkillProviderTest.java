package com.amsha.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

import com.amsha.core.skill.SkillAdaptationManager;
import com.amsha.core.skill.SkillDefinition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillsJarSkillProviderTest {

    @Mock
    SkillDefinitionParser parser;

    @Mock
    SkillAdaptationManager adaptationManager;

    @InjectMocks
    SkillsJarSkillProvider provider;

    @Test
    void testMetadata() {
        assertEquals("skillsjars", provider.sourceName());
        assertEquals(50, provider.priority());
    }

    @Test
    void testLoadHandlesEmptyOrIndex() {
        List<SkillDefinition> loaded = provider.load();
        assertNotNull(loaded);
    }

    @Test
    void testMaybeAugmentBrowserPreviewNull() {
        assertNull(SkillsJarSkillProvider.maybeAugmentBrowserPreview(null, "/tmp/home"));
    }

    @Test
    void testMaybeAugmentBrowserPreviewNonBrowser() {
        SkillDefinition def = new SkillDefinition(
                "s1", "name", "desc", List.of(), List.of(), 5, "system prompt", "/home", false, null);
        SkillDefinition result = SkillsJarSkillProvider.maybeAugmentBrowserPreview(def, "/home");
        assertEquals(def, result);
    }

    @Test
    void testMaybeAugmentBrowserPreviewBrowserEnabled() {
        SkillDefinition def = new SkillDefinition(
                "s1", "name", "desc", List.of(), List.of(), 5, "original prompt", "/home", true, "templates");
        SkillDefinition result = SkillsJarSkillProvider.maybeAugmentBrowserPreview(def, "/home");
        assertNotNull(result);
        assertTrue(result.systemPrompt().contains("BROWSER PREVIEW RULES"));
        assertTrue(result.systemPrompt().contains("original prompt"));
    }

    @Test
    void testConflictingSectionsStripped() {
        SkillDefinition parsedDef =
                new SkillDefinition("s1", "n1", "d1", List.of(), List.of(), 5, "clean", "/tmp", false, null);
        when(adaptationManager.isAdaptiveEnabled()).thenReturn(false);
        when(parser.parse(anyString(), any())).thenReturn(parsedDef);

        List<SkillDefinition> skills = provider.load();
        assertNotNull(skills);
    }

    @Test
    void testLoadSkillFromFile(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path scriptsDir = skillDir.resolve("scripts");
        Files.createDirectories(scriptsDir);
        Files.writeString(scriptsDir.resolve("run.sh"), "#!/bin/bash\necho ok\n");

        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "# Skill\nallowed-tools: Bash()\n## Decision Tree\nConflicting content\n");

        SkillDefinition parsedDef = new SkillDefinition(
                "s2", "n2", "d2", List.of(), List.of(), 5, "clean2", skillDir.toString(), false, null);
        when(parser.parse(anyString(), eq(skillDir.toString()))).thenReturn(parsedDef);
        when(adaptationManager.isAdaptiveEnabled()).thenReturn(false);

        var loadMethod = provider.getClass().getDeclaredMethod("loadSkillFromFile", Path.class, List.class);
        loadMethod.setAccessible(true);
        List<SkillDefinition> skills = new ArrayList<>();
        loadMethod.invoke(provider, skillMd, skills);

        assertFalse(skills.isEmpty());
    }

    @Test
    void testLoadSkillFromFileAdaptiveEnabled(@TempDir Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("my-skill-adaptive");
        Files.createDirectories(skillDir);
        Path scriptsDir = skillDir.resolve("scripts");
        Files.createDirectories(scriptsDir);

        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "# Skill\n## Prerequisites\nSkip me\n");

        SkillDefinition parsedDef = new SkillDefinition(
                "s3", "n3", "d3", List.of(), List.of(), 5, "clean3", skillDir.toString(), false, null);
        when(parser.parse(anyString(), eq(skillDir.toString()))).thenReturn(parsedDef);
        when(adaptationManager.isAdaptiveEnabled()).thenReturn(true);

        var loadMethod = provider.getClass().getDeclaredMethod("loadSkillFromFile", Path.class, List.class);
        loadMethod.setAccessible(true);
        List<SkillDefinition> skills = new ArrayList<>();
        loadMethod.invoke(provider, skillMd, skills);

        assertFalse(skills.isEmpty());
    }

    @Test
    void testScanDirectory(@TempDir Path tempDir) throws Exception {
        Path skillSubDir = tempDir.resolve("META-INF/skills/test-provider/test-skill");
        Files.createDirectories(skillSubDir);
        Path skillFile = skillSubDir.resolve("SKILL.md");
        Files.writeString(skillFile, "# Test Skill\n");

        var scanDirMethod = provider.getClass().getDeclaredMethod("scanDirectory", URL.class, List.class);
        scanDirMethod.setAccessible(true);

        List<SkillDefinition> skills = new ArrayList<>();
        scanDirMethod.invoke(provider, tempDir.toUri().toURL(), skills);
        assertNotNull(skills);
    }

    @Test
    void testLoadFromExtractedSkills(@TempDir Path tempDir) throws Exception {
        var loadExtracted = provider.getClass().getDeclaredMethod("loadFromExtractedSkills", List.class);
        loadExtracted.setAccessible(true);

        List<SkillDefinition> skills = new ArrayList<>();
        assertDoesNotThrow(() -> loadExtracted.invoke(provider, skills));
    }

    @Test
    void testWriteSourceMetadata(@TempDir Path tempDir) throws Exception {
        JarFile mockJar = mock(JarFile.class);
        when(mockJar.getName()).thenReturn("com.skillsjars.test-skill-1.2.3.jar");

        var writeMeta =
                provider.getClass().getDeclaredMethod("writeSourceMetadata", Path.class, String.class, JarFile.class);
        writeMeta.setAccessible(true);

        assertDoesNotThrow(() -> writeMeta.invoke(provider, tempDir, "test-skill", mockJar));
        assertTrue(Files.exists(tempDir.resolve(".skill-source.json")));
    }
}
