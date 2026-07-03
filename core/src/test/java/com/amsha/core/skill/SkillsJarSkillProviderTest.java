package com.amsha.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
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
