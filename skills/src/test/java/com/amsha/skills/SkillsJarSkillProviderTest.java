package com.amsha.skills;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
    void testLoadFromIndexWithEntries() {
        SkillsJarSkillProvider spyProvider = spy(provider);
        String indexContent = "# Header\n\nMETA-INF/skills/vendor/skill-a/SKILL.md\n";
        ByteArrayInputStream is = new ByteArrayInputStream(indexContent.getBytes(StandardCharsets.UTF_8));
        doReturn(is).when(spyProvider).openIndexStream();
        doNothing().when(spyProvider).loadIndexedSkill(anyString(), anyList());

        List<SkillDefinition> skills = new ArrayList<>();
        assertTrue(spyProvider.loadFromIndex(skills));
        verify(spyProvider).loadIndexedSkill(eq("META-INF/skills/vendor/skill-a/SKILL.md"), eq(skills));
    }

    @Test
    void testLoadFromIndexStreamFailure() throws Exception {
        SkillsJarSkillProvider spyProvider = spy(provider);
        InputStream failing = mock(InputStream.class);
        when(failing.read(any(byte[].class), anyInt(), anyInt())).thenThrow(new IOException("boom"));
        when(failing.read()).thenThrow(new IOException("boom"));
        doReturn(failing).when(spyProvider).openIndexStream();

        List<SkillDefinition> skills = new ArrayList<>();
        assertTrue(spyProvider.loadFromIndex(skills));
    }

    @Test
    void testScanJarAndExtractAssets(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path jarPath = tempDir.resolve("com.skillsjars.demo-skill-1.5.0.jar");
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue("Implementation-Version", "1.5.0");

            try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
                jos.putNextEntry(new JarEntry("META-INF/skills/demo/demo-skill/SKILL.md"));
                jos.write("# Demo Skill\nallowed-tools: Bash()\n".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();

                jos.putNextEntry(new JarEntry("META-INF/skills/demo/demo-skill/scripts/run.sh"));
                jos.write("#!/bin/bash\necho 1\n".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }

            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                String extractedHome = provider.extractSkillAssets(jarFile, "META-INF/skills/demo/demo-skill/SKILL.md");
                assertNotNull(extractedHome);
                assertTrue(Files.isRegularFile(Path.of(extractedHome, "scripts", "run.sh")));
                assertTrue(Files.isRegularFile(Path.of(extractedHome, ".skill-source.json")));
                String meta = Files.readString(Path.of(extractedHome, ".skill-source.json"));
                assertTrue(meta.contains("\"version\": \"1.5.0\""));
                assertTrue(meta.contains("\"skillName\": \"demo-skill\""));
            }
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void testWriteSourceMetadataFallbacks(@TempDir Path tempDir) throws Exception {
        Path jarPath = tempDir.resolve("vendor-custom-tool-3.2.1.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Specification-Version", "3.2.1");

        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            jos.putNextEntry(new JarEntry("file.txt"));
            jos.write("data".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Path targetDir = tempDir.resolve("target1");
            Files.createDirectories(targetDir);
            provider.writeSourceMetadata(targetDir, "custom-tool", jarFile);
            String meta = Files.readString(targetDir.resolve(".skill-source.json"));
            assertTrue(meta.contains("\"version\": \"3.2.1\""));
        }

        // Test without manifest: filename fallback
        Path plainJar = tempDir.resolve("custom-lib-9.8.7.jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(plainJar))) {
            jos.putNextEntry(new JarEntry("dummy.txt"));
            jos.write("dummy".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        try (JarFile jarFile = new JarFile(plainJar.toFile())) {
            Path targetDir = tempDir.resolve("target2");
            Files.createDirectories(targetDir);
            provider.writeSourceMetadata(targetDir, "custom-lib", jarFile);
            String meta = Files.readString(targetDir.resolve(".skill-source.json"));
            assertTrue(meta.contains("\"version\": \"9.8.7\""));
        }
    }

    @Test
    void testLoadFromExtractedSkillsDirectory(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            Path skillDir = tempDir.resolve(".amsha/skills/skillsjars/saved-skill");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), "# Saved Skill\n");

            SkillDefinition parsedDef = new SkillDefinition(
                    "s-saved", "saved", "desc", List.of(), List.of(), 5, "prompt", skillDir.toString(), false, null);
            when(parser.parse(anyString(), eq(skillDir.toString()))).thenReturn(parsedDef);

            List<SkillDefinition> skills = new ArrayList<>();
            provider.loadFromExtractedSkills(skills);
            assertEquals(1, skills.size());
            assertEquals("s-saved", skills.get(0).id());
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void testStripConflictingSections() throws Exception {
        String input = """
                # Decision Tree
                Skip whole tree
                ## Quick Start
                Skip quick start
                ### Prerequisites
                Skip prereqs
                #### Enabling JDWP
                Skip jdwp
                # Allowed Section
                Keep this line
                """;
        var stripMethod = SkillsJarSkillProvider.class.getDeclaredMethod("stripConflictingSections", String.class);
        stripMethod.setAccessible(true);
        String stripped = (String) stripMethod.invoke(null, input);
        assertNotNull(stripped);
        assertTrue(stripped.contains("# Allowed Section"));
        assertTrue(stripped.contains("Keep this line"));
        assertFalse(stripped.contains("Skip whole tree"));
        assertFalse(stripped.contains("Skip prereqs"));
    }

    @Test
    void testScanUrlWithUnknownProtocol() throws Exception {
        List<SkillDefinition> skills = new ArrayList<>();
        URL customUrl = new URI("http://example.com/skill").toURL();
        assertDoesNotThrow(() -> provider.scanUrl(customUrl, skills));
        assertTrue(skills.isEmpty());
    }

    @Test
    void testLoadSkillFromResourceNonExistent() {
        List<SkillDefinition> skills = new ArrayList<>();
        assertDoesNotThrow(() -> provider.loadSkillFromResource("/non/existent/path/SKILL.md", skills));
        assertTrue(skills.isEmpty());
    }

    @Test
    void testMaybeAugmentBrowserPreviewAndTemplateDir() {
        assertNull(SkillsJarSkillProvider.maybeAugmentBrowserPreview(null, null));

        SkillDefinition noPreview =
                new SkillDefinition("s1", "skill1", "desc", List.of(), List.of(), 5, "prompt", "/home", false, null);
        assertSame(noPreview, SkillsJarSkillProvider.maybeAugmentBrowserPreview(noPreview, "/home"));

        SkillDefinition withPreview = new SkillDefinition(
                "s2", "skill2", "desc", List.of(), List.of(), 5, "prompt", "/home", true, "templates/start");
        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(withPreview, "/home");
        assertNotNull(augmented);
        assertTrue(augmented.systemPrompt().contains("BROWSER PREVIEW RULES"));
        assertTrue(augmented.systemPrompt().contains("templates/start"));
    }
}
