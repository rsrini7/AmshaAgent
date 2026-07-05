package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SkillsJarSkillProviderTest {

    private Path tempHome;
    private String originalUserHome;

    @BeforeEach
    void setUp() throws IOException {
        originalUserHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("skill-provider-test-home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        if (tempHome != null && Files.exists(tempHome)) {
            Files.walk(tempHome).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // ignore cleanup failures
                }
            });
        }
    }

    @Test
    void testExtractSkillAssetsIncludesSkillMd() throws Exception {
        Path jarFile = createSkillJar();
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            SkillsJarSkillProvider provider = new SkillsJarSkillProvider();
            Method extractMethod =
                    SkillsJarSkillProvider.class.getDeclaredMethod("extractSkillAssets", JarFile.class, String.class);
            extractMethod.setAccessible(true);

            String skillMdPath = "META-INF/skills/brunoborges/jdb-agentic-debugger/jdb-debugger/SKILL.md";
            String extractedPath = (String) extractMethod.invoke(provider, jar, skillMdPath);

            assertTrue(extractedPath != null && !extractedPath.isBlank(), "Expected a non-empty extracted skill path");
            Path extractedDir = Path.of(extractedPath);
            assertTrue(Files.isDirectory(extractedDir), "Extracted skill directory should exist");
            assertTrue(
                    Files.exists(extractedDir.resolve("SKILL.md")),
                    "SKILL.md should be extracted into the skill directory");
            assertTrue(
                    Files.exists(extractedDir.resolve("scripts/jdb-attach.sh")),
                    "Script files should be extracted into the skill directory");
            assertTrue(
                    Files.exists(extractedDir.resolve("references/jdwp-options.md")),
                    "Reference files should be extracted into the skill directory");

            String actualName = extractedDir.getFileName().toString();
            assertEquals("jdb-debugger", actualName, "Skill directory should be named after the skill");
        }
    }

    @Test
    void testBrowserPreviewRulesApplyToHtmlSkillsGenerically() throws Exception {
        Path skillHome = Files.createTempDirectory(tempHome, "viewer-skill");
        Files.createDirectories(skillHome.resolve("output"));

        SkillDefinition base = new SkillDefinition(
                "id",
                "generic-viewer",
                "Render artifacts",
                List.of(),
                List.of(),
                5,
                "BASE SYSTEM PROMPT",
                skillHome.toString());

        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(
                base, "This skill generates .html files and browser artifacts.", skillHome.toString());

        assertTrue(augmented.systemPrompt().contains("BROWSER PREVIEW RULES"));
        assertTrue(augmented.systemPrompt().contains(skillHome.resolve("output").toString()));
        assertTrue(augmented.systemPrompt().contains("default browser"));
    }

    @Test
    void testBrowserPreviewRulesDoNotApplyToNonHtmlSkills() {
        Path skillHome = tempHome.resolve("plain-skill");
        SkillDefinition base = new SkillDefinition(
                "id",
                "plain-skill",
                "Plain skill",
                List.of(),
                List.of(),
                5,
                "BASE SYSTEM PROMPT",
                skillHome.toString());

        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(
                base, "This skill only reads text.", skillHome.toString());

        assertSame(base, augmented, "Non-HTML skills should be returned unchanged");
    }

    private Path createSkillJar() throws IOException {
        Path jarFile = Files.createTempFile("jdb-debugger-skill", ".jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarFile), manifest)) {
            addEntry(
                    jos,
                    "META-INF/skills/brunoborges/jdb-agentic-debugger/jdb-debugger/SKILL.md",
                    "---\nname: jdb-debugger\ndescription: Debug Java processes\n---\nYou are a JDB debugger skill.\n");
            addEntry(
                    jos,
                    "META-INF/skills/brunoborges/jdb-agentic-debugger/jdb-debugger/scripts/jdb-attach.sh",
                    "#!/bin/bash\necho attach\n");
            addEntry(
                    jos,
                    "META-INF/skills/brunoborges/jdb-agentic-debugger/jdb-debugger/references/jdwp-options.md",
                    "# JDWP options\n");
        }
        return jarFile;
    }

    private static void addEntry(JarOutputStream jos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        jos.putNextEntry(entry);
        jos.write(content.getBytes());
        jos.closeEntry();
    }
}
