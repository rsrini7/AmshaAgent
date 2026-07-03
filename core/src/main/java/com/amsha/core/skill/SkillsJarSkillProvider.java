package com.amsha.core.skill;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

@ApplicationScoped
public class SkillsJarSkillProvider implements SkillProvider {

    private static final Logger LOG = Logger.getLogger(SkillsJarSkillProvider.class);
    private static final String SKILLS_DIR = "META-INF/skills";
    private static final String INDEX_PATH = "META-INF/skills/skills-index.txt";

    @Inject
    SkillDefinitionParser parser;

    @Override
    public String sourceName() {
        return "skillsjars";
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public List<SkillDefinition> load() {
        List<SkillDefinition> skills = new ArrayList<>();

        if (loadFromIndex(skills)) {
            return skills;
        }

        loadFromClasspath(skills);
        return skills;
    }

    private boolean loadFromIndex(List<SkillDefinition> skills) {
        InputStream indexStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(INDEX_PATH);

        if (indexStream == null) {
            LOG.debugf("SkillsJars index %s not found. Falling back to classpath scan.", INDEX_PATH);
            return false;
        }

        LOG.infof("Found SkillsJars index file. Loading pre-indexed skills...");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                loadSkillFromResource("/" + line, skills);
            }
        } catch (Exception e) {
            LOG.errorf("Failure reading SkillsJars index %s: %s", INDEX_PATH, LogUtil.rootMessage(e));
        }
        return !skills.isEmpty();
    }

    private void loadFromClasspath(List<SkillDefinition> skills) {
        try {
            Enumeration<URL> resources =
                    Thread.currentThread().getContextClassLoader().getResources(SKILLS_DIR);

            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                scanUrl(url, skills);
            }
        } catch (IOException e) {
            LOG.warnf("Failed to scan classpath for SkillsJars: %s", e.getMessage());
        }

        if (!skills.isEmpty()) {
            LOG.infof("Loaded %d SkillsJars skill(s) from classpath", skills.size());
        } else {
            LOG.debug("No SkillsJars skills found on classpath");
        }
    }

    private void scanUrl(URL url, List<SkillDefinition> skills) {
        try {
            String protocol = url.getProtocol();
            if ("jar".equals(protocol)) {
                scanJar(url, skills);
            } else if ("file".equals(protocol)) {
                scanDirectory(url, skills);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to scan SkillsJars URL %s: %s", url, e.getMessage());
        }
    }

    private void scanJar(URL url, List<SkillDefinition> skills) {
        try {
            URLConnection conn = url.openConnection();
            if (conn instanceof JarURLConnection jarConn) {
                try (JarFile jarFile = jarConn.getJarFile()) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.startsWith(SKILLS_DIR + "/") && name.endsWith("/SKILL.md")) {
                            // Extract companion scripts/references to a temp directory
                            String skillHome = extractSkillAssets(jarFile, name);
                            loadSkillFromResource("/" + name, skills, skillHome);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to scan JAR %s for SkillsJars: %s", url, e.getMessage());
        }
    }

    /**
     * Extracts non-SKILL.md files (scripts/, references/, etc.) from the skill
     * directory in the JAR to a temp directory so host_exec / sandbox_exec can
     * run them.
     *
     * @return the extracted skill home path, or null if nothing was extracted
     */
    private String extractSkillAssets(JarFile jarFile, String skillMdPath) {
        // e.g. META-INF/skills/brunoborges/jdb-agentic-debugger/jdb-debugger/SKILL.md
        String skillDir = skillMdPath.substring(0, skillMdPath.lastIndexOf('/') + 1);
        String skillName = skillDir.endsWith("/") ? skillDir.substring(0, skillDir.length() - 1) : skillDir;
        skillName = skillName.substring(skillName.lastIndexOf('/') + 1);

        try {
            Path tempDir = Files.createTempDirectory("amsha-skill-" + skillName + "-");
            tempDir.toFile().deleteOnExit();
            boolean extracted = false;

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // Only extract files under the same skill dir, skip SKILL.md itself
                if (name.startsWith(skillDir) && !name.equals(skillMdPath) && !entry.isDirectory()) {
                    String relativePath = name.substring(skillDir.length());
                    Path target = tempDir.resolve(relativePath);
                    Files.createDirectories(target.getParent());
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    // Preserve execute permission for scripts
                    if (relativePath.endsWith(".sh") || relativePath.startsWith("scripts/")) {
                        target.toFile().setExecutable(true);
                    }
                    extracted = true;
                }
            }

            if (extracted) {
                LOG.infof("[SkillsJar] Extracted skill assets for '%s' to %s", skillName, tempDir);
                return tempDir.toString();
            }
            // Nothing to extract — clean up
            Files.deleteIfExists(tempDir);
            return null;
        } catch (Exception e) {
            LOG.warnf("[SkillsJar] Failed to extract assets for '%s': %s", skillName, e.getMessage());
            return null;
        }
    }

    private void scanDirectory(URL url, List<SkillDefinition> skills) {
        try {
            Path dir = Path.of(url.toURI());
            Files.walk(dir).filter(p -> p.toString().endsWith("/SKILL.md")).forEach(p -> {
                String relativePath = dir.relativize(p).toString().replace("\\", "/");
                String resourcePath = SKILLS_DIR + "/" + relativePath;
                loadSkillFromResource("/" + resourcePath, skills);
            });
        } catch (Exception e) {
            LOG.warnf("Failed to scan directory %s for SkillsJars: %s", url, e.getMessage());
        }
    }

    private void loadSkillFromResource(String classpathPath, List<SkillDefinition> skills) {
        loadSkillFromResource(classpathPath, skills, null);
    }

    private void loadSkillFromResource(String classpathPath, List<SkillDefinition> skills, String skillHome) {
        try (InputStream stream = getClass().getResourceAsStream(classpathPath)) {
            if (stream == null) {
                LOG.warnf("SkillsJar skill '%s' not found on classpath", classpathPath);
                return;
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            skills.add(parser.parse(content, skillHome));
        } catch (Exception e) {
            LOG.warnf("Failed to load SkillsJar skill %s: %s", classpathPath, LogUtil.rootMessage(e));
        }
    }
}
