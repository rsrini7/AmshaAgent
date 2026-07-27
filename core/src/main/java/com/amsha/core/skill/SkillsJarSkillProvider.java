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
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.amsha.common.util.LogUtil;

import org.jboss.logging.Logger;

@ApplicationScoped
public class SkillsJarSkillProvider implements SkillProvider {

    private static final Logger LOG = Logger.getLogger(SkillsJarSkillProvider.class);
    private static final String SKILLS_DIR = "META-INF/skills";
    private static final String INDEX_PATH = "META-INF/skills/skills-index.txt";

    @Inject
    SkillDefinitionParser parser;

    @Inject
    SkillAdaptationManager adaptationManager;

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
        if (skills.isEmpty()) {
            loadFromExtractedSkills(skills);
        }
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
                loadIndexedSkill(line, skills);
            }
        } catch (Exception e) {
            LOG.errorf("Failure reading SkillsJars index %s: %s", INDEX_PATH, LogUtil.rootMessage(e));
        }
        // The index is authoritative when present, including when it intentionally contains
        // no skills. Falling back on an empty index would resurrect stale extracted skills
        // after their Maven dependency has been removed.
        return true;
    }

    /** Loads an indexed resource and extracts its companion files for native/indexed mode. */
    private void loadIndexedSkill(String resourcePath, List<SkillDefinition> skills) {
        String classpathPath = "/" + resourcePath;
        try {
            URL resource = Thread.currentThread().getContextClassLoader().getResource(resourcePath);
            if (resource != null && "jar".equals(resource.getProtocol())) {
                URLConnection connection = resource.openConnection();
                if (connection instanceof JarURLConnection jarConnection) {
                    try (JarFile jarFile = jarConnection.getJarFile()) {
                        String skillHome = extractSkillAssets(jarFile, resourcePath);
                        loadSkillFromResource(classpathPath, skills, skillHome);
                        return;
                    }
                }
            }
            // Exploded classpaths have no JarFile to extract; use the parent directory directly.
            if (resource != null && "file".equals(resource.getProtocol())) {
                Path file = Path.of(resource.toURI());
                loadSkillFromFile(file, skills);
                return;
            }

            // GraalVM native images expose indexed resources through getResourceAsStream()
            // without a URL. Reuse the stable extraction location populated by the build/JVM
            // loader so host_exec receives real output/template paths instead of dist-relative ones.
            String skillName = resourcePath.substring(0, resourcePath.lastIndexOf('/'));
            skillName = skillName.substring(skillName.lastIndexOf('/') + 1);
            Path extractedSkill =
                    Path.of(System.getProperty("user.home"), ".amsha", "skills", "skillsjars", skillName, "SKILL.md");
            if (Files.isRegularFile(extractedSkill)) {
                loadSkillFromFile(extractedSkill, skills);
                return;
            }
        } catch (Exception e) {
            LOG.warnf("Failed to extract indexed SkillsJar skill %s: %s", resourcePath, LogUtil.rootMessage(e));
        }
        loadSkillFromResource(classpathPath, skills);
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
     * directory in the JAR to ~/.amsha/skills/skillsjars/<skillName>/
     *
     * @return the extracted skill home path, or null if nothing was extracted
     */
    private String extractSkillAssets(JarFile jarFile, String skillMdPath) {
        // e.g. META-INF/skills/brunoborges/jdb-agentic-debugger/jdb-debugger/SKILL.md
        String skillDir = skillMdPath.substring(0, skillMdPath.lastIndexOf('/') + 1);
        String skillName = skillDir.endsWith("/") ? skillDir.substring(0, skillDir.length() - 1) : skillDir;
        skillName = skillName.substring(skillName.lastIndexOf('/') + 1);

        // Use stable path: ~/.amsha/skills/skillsjars/<skillName>/
        Path amshaHome = Path.of(System.getProperty("user.home"), ".amsha", "skills", "skillsjars", skillName);

        try {
            Files.createDirectories(amshaHome);
            boolean extracted = false;

            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                // Extract all companion files for the skill, including SKILL.md itself.
                if (name.startsWith(skillDir) && !entry.isDirectory()) {
                    String relativePath = name.substring(skillDir.length());
                    Path target = amshaHome.resolve(relativePath);
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
                // Write source tracking metadata
                writeSourceMetadata(amshaHome, skillName, jarFile);
                LOG.infof("[SkillsJar] Extracted skill assets for '%s' to %s", skillName, amshaHome);
                return amshaHome.toString();
            }
            return null;
        } catch (Exception e) {
            LOG.warnf("[SkillsJar] Failed to extract assets for '%s': %s", skillName, e.getMessage());
            return null;
        }
    }

    /**
     * Writes .skill-source.json to track where the skill came from.
     */
    private void writeSourceMetadata(Path skillDir, String skillName, JarFile jarFile) {
        try {
            Path metaFile = skillDir.resolve(".skill-source.json");
            String version = null;
            if (jarFile.getManifest() != null) {
                var attrs = jarFile.getManifest().getMainAttributes();
                version = attrs.getValue("Bundle-Version");
                if (version == null) {
                    version = attrs.getValue("Implementation-Version");
                }
                if (version == null) {
                    version = attrs.getValue("Specification-Version");
                }
            }
            if (version == null) {
                try {
                    String fileName = Path.of(jarFile.getName()).getFileName().toString();
                    if (fileName.endsWith(".jar")) {
                        fileName = fileName.substring(0, fileName.length() - 4);
                    }
                    String prefix = "com.skillsjars." + skillName + "-";
                    if (fileName.startsWith(prefix)) {
                        version = fileName.substring(prefix.length());
                    } else {
                        int lastHyphen = fileName.lastIndexOf('-');
                        if (lastHyphen != -1) {
                            version = fileName.substring(lastHyphen + 1);
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
            if (version == null) {
                version = "unknown";
            }
            String json = String.format(
                    "{\n  \"source\": \"skillsjars\",\n  \"skillName\": \"%s\",\n  \"version\": \"%s\",\n  \"jarFile\": \"%s\",\n  \"extractedAt\": \"%s\"\n}",
                    skillName,
                    version,
                    jarFile.getName().replace("\\", "/"),
                    Instant.now().toString());
            Files.writeString(metaFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOG.debugf("[SkillsJar] Could not write source metadata for '%s': %s", skillName, e.getMessage());
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

    /**
     * Native Image cannot enumerate dependency JAR directories. Skills that a JVM
     * run previously extracted to the stable SkillsJars home remain usable by the
     * native distribution as a fallback.
     */
    private void loadFromExtractedSkills(List<SkillDefinition> skills) {
        Path skillsHome = Path.of(System.getProperty("user.home"), ".amsha", "skills", "skillsjars");
        if (!Files.isDirectory(skillsHome)) {
            return;
        }
        try (Stream<Path> children = Files.list(skillsHome)) {
            children.map(path -> path.resolve("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .forEach(skillFile -> loadSkillFromFile(skillFile, skills));
        } catch (Exception e) {
            LOG.warnf("Failed to load extracted SkillsJars from %s: %s", skillsHome, e.getMessage());
        }
        if (!skills.isEmpty()) {
            LOG.infof("Loaded %d SkillsJars skill(s) from extracted skill home", skills.size());
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
            loadSkillContent(content, classpathPath, skills, skillHome);
        } catch (Exception e) {
            LOG.warnf("Failed to load SkillsJar skill %s: %s", classpathPath, LogUtil.rootMessage(e));
        }
    }

    private void loadSkillFromFile(Path skillFile, List<SkillDefinition> skills) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            loadSkillContent(
                    content, skillFile.toString(), skills, skillFile.getParent().toString());
        } catch (Exception e) {
            LOG.warnf("Failed to load extracted SkillsJar skill %s: %s", skillFile, LogUtil.rootMessage(e));
        }
    }

    private void loadSkillContent(String content, String source, List<SkillDefinition> skills, String skillHome) {
        try {

            // Inject execution override at the TOP of the system prompt (before any decision tree)
            if (skillHome != null && !skillHome.isBlank()) {
                String scriptsDir = skillHome + "/scripts";
                boolean hasScripts = Files.isDirectory(Path.of(scriptsDir));
                if (hasScripts) {
                    if (adaptationManager.isAdaptiveEnabled()) {
                        // Adaptive mode: let the LLM adapt the skill for the environment
                        // Only strip conflicting sections, don't add overrides
                        content = stripConflictingSections(content);
                        LOG.infof("[SkillsJar] Adaptive mode enabled for '%s' — LLM will adapt", source);
                    } else {
                        // Non-adaptive mode: apply our overrides
                        content = stripConflictingSections(content);

                        // Override allowed-tools: map Bash(...) to hostexec(...) for host script access
                        content = content.replaceAll("(?i)(allowed-tools:.*?)Bash\\(", "$1hostexec(");

                        String override = "\n\n# CRITICAL EXECUTION RULES (OVERRIDE ALL BELOW)\n"
                                + "1. NEVER run prerequisite commands separately — the skill scripts handle discovery and validation internally.\n"
                                + "2. Call the skill scripts DIRECTLY with ONE host_exec call per task.\n"
                                + "3. For non-interactive tasks, use scripts that accept input via flags or stdin — avoid interactive/blocking scripts.\n"
                                + "4. Chain multiple commands with && (Linux/macOS) or && (Windows CMD) or ; (PowerShell) if combining in one call.\n\n";
                        content = override + content;
                    }
                }
            }

            SkillDefinition definition = parser.parse(content, skillHome);
            skills.add(maybeAugmentBrowserPreview(definition, skillHome));
        } catch (Exception e) {
            LOG.warnf("Failed to parse SkillsJar skill %s: %s", source, LogUtil.rootMessage(e));
        }
    }

    /**
     * Strips SKILL.md sections that encourage the LLM to run prerequisite commands
     * separately (e.g. "Decision Tree", "Quick Start", "Prerequisites", "Enabling JDWP").
     * Generic — works for any skill, not specific to any tool.
     */
    private static String stripConflictingSections(String content) {
        // Remove markdown sections whose heading contains conflicting keywords
        String[] conflictingKeywords = {"decision tree", "quick start", "prerequisites", "enabling "};
        String[] lines = content.split("\n");
        StringBuilder result = new StringBuilder();
        boolean skipping = false;
        int skipLevel = 0;

        for (String line : lines) {
            Matcher heading = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            if (heading.matches()) {
                String title = heading.group(2).toLowerCase();
                int level = heading.group(1).length();
                boolean conflict = false;
                for (String kw : conflictingKeywords) {
                    if (title.contains(kw)) {
                        conflict = true;
                        break;
                    }
                }
                if (conflict) {
                    skipping = true;
                    skipLevel = level;
                    continue;
                } else if (skipping && level <= skipLevel) {
                    skipping = false;
                }
            }
            if (!skipping) {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    static SkillDefinition maybeAugmentBrowserPreview(SkillDefinition definition, String skillHome) {
        if (definition == null) {
            return definition;
        }
        if (!definition.browserPreview()) {
            return definition;
        }

        String outputDir = skillHome != null && !skillHome.isBlank() ? skillHome + "/output" : "output";
        String templateDir = resolveTemplateDir(definition, skillHome);
        String templateInstruction = templateDir == null
                ? ""
                : "2. Read the starter templates under `" + templateDir
                        + "` only when needed; never open a template file in a browser.\n";
        String browserOverride = """

                # BROWSER PREVIEW RULES (OVERRIDE ALL BELOW)
                1. When you generate HTML, JS, or CSS artifacts, write them into `%s`.
                %s3. Do not paste raw HTML into Telegram or the final answer when the artifact is meant for the browser.
                4. Use `host_exec` to list the files in `%s` and open the generated `.html` artifact with the host's default browser opener (`open` on macOS, `xdg-open` on Linux, `start` on Windows).
                5. If multiple HTML files exist, open the main viewer file you just created.
                6. If the browser fails to open, keep the artifact in `%s` and reply with a brief summary only.
                7. Never dump the generated HTML back into Telegram when a browser preview is possible.

                """;
        String systemPrompt =
                String.format(browserOverride, outputDir, templateInstruction, outputDir, outputDir, outputDir)
                        + definition.systemPrompt();
        return new SkillDefinition(
                definition.id(),
                definition.name(),
                definition.description(),
                definition.triggerPatterns(),
                definition.toolCapabilities(),
                definition.maxIterations(),
                systemPrompt,
                definition.skillHome(),
                true,
                definition.templateDir());
    }

    private static String resolveTemplateDir(SkillDefinition definition, String skillHome) {
        String relative = definition.templateDir();
        if (relative == null || relative.isBlank()) return null;
        Path path = Path.of(relative);
        if (!path.isAbsolute() && skillHome != null && !skillHome.isBlank())
            path = Path.of(skillHome).resolve(path);
        return path.normalize().toString();
    }
}
