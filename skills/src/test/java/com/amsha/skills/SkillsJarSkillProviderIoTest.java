package com.amsha.skills;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * I/O-path tests for {@link SkillsJarSkillProvider}.
 *
 * <p>The sibling {@code SkillsJarSkillProviderTest} covers the pure helpers (metadata, section stripping, browser
 * augmentation, index parsing) and the asset-extraction happy path. What it cannot reach is the discovery plumbing:
 * the classpath scan, the {@code jar:}/{@code file:} dispatch, the GraalVM native fallback, and every
 * {@code catch} that turns an unreadable JAR, an unwalkable directory or an unwritable skill home into a warning
 * rather than a failed load. Those paths need a real JAR, a real classpath entry and a controllable
 * {@code user.home} — which is what this class supplies.
 *
 * <p>{@code META-INF/skills/amsha-test/unit-skill/SKILL.md} is a test-classpath resource. It exists so
 * {@code getResources("META-INF/skills")} returns a directory (exercising {@code loadFromClasspath} →
 * {@code scanDirectory}) and so {@code getClass().getResourceAsStream(...)} has something to find: the provider
 * resolves its <em>stream</em> through the application classloader but its <em>JAR</em> through the context
 * classloader, so the two must be arranged separately.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillsJarSkillProviderIoTest {

    /** Must match the fixture under {@code src/test/resources}. */
    private static final String CLASSPATH_SKILL = "META-INF/skills/amsha-test/unit-skill/SKILL.md";

    @Mock
    SkillDefinitionParser parser;

    @Mock
    SkillAdaptationManager adaptationManager;

    @InjectMocks
    SkillsJarSkillProvider provider;

    private static SkillDefinition def(String id) {
        return new SkillDefinition(id, id, "desc", List.of(), List.of(), 5, "prompt", "/home", false, null);
    }

    private static void writeJar(Path jarPath, Map<String, String> entries) throws IOException {
        writeJar(jarPath, entries, null);
    }

    private static void writeJar(Path jarPath, Map<String, String> entries, Manifest manifest) throws IOException {
        try (JarOutputStream jos = manifest == null
                ? new JarOutputStream(Files.newOutputStream(jarPath))
                : new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
    }

    private static SkillDefinition previewDef(String templateDir) {
        return new SkillDefinition(
                "s-preview", "preview", "desc", List.of(), List.of(), 5, "prompt", "/home", true, templateDir);
    }

    /**
     * A URL whose protocol is {@code protocol} but whose connection is a plain {@link URLConnection} rather than the
     * protocol-specific subtype. Both {@code loadIndexedSkill} and {@code scanJar} cast the opened connection with an
     * {@code instanceof} guard precisely because this shape is reachable; nothing else can produce it.
     */
    private static URL urlWithPlainConnection(String protocol) throws Exception {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) {
                return new URLConnection(u) {
                    @Override
                    public void connect() {
                        // no-op
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(new byte[0]);
                    }
                };
            }
        };
        return new URL(protocol, "", -1, "/skill/SKILL.md", handler);
    }

    /** A context classloader that resolves every resource name to one hand-built URL. */
    private static ClassLoader returning(URL url) {
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return url;
            }
        };
    }

    private static String firstId(List<SkillDefinition> skills) {
        assertEquals(1, skills.size());
        return skills.get(0).id();
    }

    // ---- load() dispatch ----

    @Test
    void loadReturnsTheIndexResultWithoutScanningTheClasspath() {
        SkillsJarSkillProvider spyProvider = spy(provider);
        SkillDefinition indexed = def("indexed-skill");
        doAnswer(invocation -> {
                    List<SkillDefinition> target = invocation.getArgument(0);
                    target.add(indexed);
                    return true;
                })
                .when(spyProvider)
                .loadFromIndex(anyList());

        List<SkillDefinition> skills = spyProvider.load();

        assertEquals(List.of(indexed), skills);
        verify(spyProvider, never()).loadFromExtractedSkills(anyList());
    }

    @Test
    void loadDiscoversTheBundledClasspathSkill() {
        when(parser.parse(anyString(), any())).thenReturn(def("unit-skill"));

        List<SkillDefinition> skills = provider.load();

        assertEquals("unit-skill", firstId(skills));
    }

    // ---- loadIndexedSkill: the three discovery shapes ----

    @Test
    void loadIndexedSkillLoadsFromAnExplodedClasspathEntry() {
        when(parser.parse(anyString(), any())).thenReturn(def("unit-skill"));

        List<SkillDefinition> skills = new ArrayList<>();
        provider.loadIndexedSkill(CLASSPATH_SKILL, skills);

        assertEquals("unit-skill", firstId(skills));
    }

    @Test
    void loadIndexedSkillExtractsCompanionAssetsFromAJarEntry(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        ClassLoader origTccl = Thread.currentThread().getContextClassLoader();
        // Deliberately NOT the classpath fixture: ClassLoader.getResource consults the PARENT first, so a path
        // that also exists in target/test-classes would resolve to its file: URL and never reach the jar branch.
        String jarOnlySkill = "META-INF/skills/amsha-test/jar-only-skill/SKILL.md";
        Path jar = tempDir.resolve("bundle.jar");
        writeJar(
                jar,
                Map.of(
                        jarOnlySkill,
                        "# Jar Only Skill\n",
                        "META-INF/skills/amsha-test/jar-only-skill/scripts/run.sh",
                        "#!/bin/sh\necho ok\n"));
        try {
            System.setProperty("user.home", tempDir.toString());
            when(adaptationManager.isAdaptiveEnabled()).thenReturn(false);

            List<SkillDefinition> skills = new ArrayList<>();
            try (URLClassLoader contextLoader =
                    new URLClassLoader(new URL[] {jar.toUri().toURL()}, origTccl)) {
                Thread.currentThread().setContextClassLoader(contextLoader);
                provider.loadIndexedSkill(jarOnlySkill, skills);
            }

            // The jar branch's distinguishing effect: companion assets land in the stable skill home. The parse
            // itself cannot contribute here — the provider reads the stream through the application classloader,
            // which does not hold this jar-only entry.
            assertTrue(
                    Files.isRegularFile(tempDir.resolve(".amsha/skills/skillsjars/jar-only-skill/scripts/run.sh")),
                    "the jar branch must extract companion assets into the stable skill home");
        } finally {
            Thread.currentThread().setContextClassLoader(origTccl);
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void loadIndexedSkillFallsBackToThePreviouslyExtractedSkillHome(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());
            // No URL on the classpath for this path: native-image style. Only the extraction survives.
            Path extracted = tempDir.resolve(".amsha/skills/skillsjars/absent-skill/SKILL.md");
            Files.createDirectories(extracted.getParent());
            Files.writeString(extracted, "# Absent Skill\n");
            when(parser.parse(anyString(), any())).thenReturn(def("absent-skill"));

            List<SkillDefinition> skills = new ArrayList<>();
            provider.loadIndexedSkill("META-INF/skills/vendor/absent-skill/SKILL.md", skills);

            assertEquals("absent-skill", firstId(skills));
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void loadIndexedSkillFallsBackToTheClasspathWhenNothingWasEverExtracted(@TempDir Path tempDir) {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            List<SkillDefinition> skills = new ArrayList<>();
            assertDoesNotThrow(
                    () -> provider.loadIndexedSkill("META-INF/skills/vendor/never-extracted/SKILL.md", skills));

            assertTrue(skills.isEmpty());
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void loadIndexedSkillToleratesAMalformedResourcePath() {
        List<SkillDefinition> skills = new ArrayList<>();

        // No '/' means the skill-name derivation cannot index, which must warn rather than propagate.
        assertDoesNotThrow(() -> provider.loadIndexedSkill("malformed", skills));

        assertTrue(skills.isEmpty());
    }

    // ---- scanUrl dispatch and the two scanners ----

    @Test
    void scanUrlExtractsSkillsFromAJar(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        Path jar = tempDir.resolve("scanned.jar");
        writeJar(
                jar,
                Map.of(
                        CLASSPATH_SKILL,
                        "# Unit Skill\n",
                        "META-INF/skills/amsha-test/unit-skill/scripts/run.sh",
                        "#!/bin/sh\n",
                        // An entry outside META-INF/skills exercises the scan's directory-prefix guard.
                        "other/thing.txt",
                        "x",
                        // A directory entry and a non-.sh companion exercise the two extraction guards.
                        "META-INF/skills/amsha-test/unit-skill/scripts/",
                        "",
                        "META-INF/skills/amsha-test/unit-skill/scripts/data.json",
                        "{}"));
        try {
            System.setProperty("user.home", tempDir.toString());
            when(parser.parse(anyString(), any())).thenReturn(def("unit-skill"));

            List<SkillDefinition> skills = new ArrayList<>();
            provider.scanUrl(new URL("jar:" + jar.toUri() + "!/"), skills);

            assertEquals("unit-skill", firstId(skills));
            assertTrue(Files.isRegularFile(tempDir.resolve(".amsha/skills/skillsjars/unit-skill/scripts/run.sh")));
            assertTrue(
                    Files.isRegularFile(tempDir.resolve(".amsha/skills/skillsjars/unit-skill/scripts/data.json")),
                    "companion files under scripts/ are extracted even when they are not shell scripts");
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void scanUrlToleratesAJarUrlThatCannotBeOpened(@TempDir Path tempDir) throws Exception {
        List<SkillDefinition> skills = new ArrayList<>();
        URL missing = new URL("jar:" + tempDir.resolve("missing.jar").toUri() + "!/");

        assertDoesNotThrow(() -> provider.scanUrl(missing, skills));

        assertTrue(skills.isEmpty());
    }

    @Test
    void scanUrlToleratesAFileUrlThatCannotBeWalked(@TempDir Path tempDir) throws Exception {
        List<SkillDefinition> skills = new ArrayList<>();
        URL missing = tempDir.resolve("no-such-directory").toUri().toURL();

        assertDoesNotThrow(() -> provider.scanUrl(missing, skills));

        assertTrue(skills.isEmpty());
    }

    // ---- extractSkillAssets ----

    @Test
    void extractSkillAssetsReturnsNullWhenTheJarHoldsNoMatchingEntries(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("unrelated.jar");
        writeJar(jar, Map.of("other/thing.txt", "x"));

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            assertNull(provider.extractSkillAssets(jarFile, "META-INF/skills/vendor/absent/SKILL.md"));
        }
    }

    @Test
    void extractSkillAssetsReturnsNullWhenTheSkillHomeCannotBeCreated(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        Path blocker = tempDir.resolve(".amsha/skills/skillsjars");
        Files.createDirectories(blocker.getParent());
        // A regular file where the parent directory of the skill home is required.
        Files.writeString(blocker, "not a directory");
        Path jar = tempDir.resolve("bundle.jar");
        writeJar(jar, Map.of("META-INF/skills/vendor/trapped/SKILL.md", "# Trapped\n"));
        try {
            System.setProperty("user.home", tempDir.toString());

            try (JarFile jarFile = new JarFile(jar.toFile())) {
                assertNull(provider.extractSkillAssets(jarFile, "META-INF/skills/vendor/trapped/SKILL.md"));
            }
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    // ---- writeSourceMetadata version resolution ----

    @Test
    void writeSourceMetadataReadsTheVersionFromTheSkillsJarsFileName(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("com.skillsjars.unit-skill-2.4.6.jar");
        writeJar(jar, Map.of("x.txt", "x"));
        Path target = tempDir.resolve("meta-prefixed");
        Files.createDirectories(target);

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            provider.writeSourceMetadata(target, "unit-skill", jarFile);
        }

        assertTrue(Files.readString(target.resolve(".skill-source.json")).contains("\"version\": \"2.4.6\""));
    }

    @Test
    void writeSourceMetadataFallsBackToUnknownWhenTheJarNameIsUnusable(@TempDir Path tempDir) throws Exception {
        JarFile jarFile = mock(JarFile.class);
        // A NUL byte makes Path.of throw, which is the inner catch's only route.
        when(jarFile.getName()).thenReturn("unusable\u0000name.jar");
        Path target = tempDir.resolve("meta-unknown");
        Files.createDirectories(target);

        provider.writeSourceMetadata(target, "unit-skill", jarFile);

        assertTrue(Files.readString(target.resolve(".skill-source.json")).contains("\"version\": \"unknown\""));
    }

    @Test
    void writeSourceMetadataSwallowsAnUnwritableTarget(@TempDir Path tempDir) {
        JarFile jarFile = mock(JarFile.class);
        when(jarFile.getName()).thenReturn("plain.jar");

        assertDoesNotThrow(
                () -> provider.writeSourceMetadata(tempDir.resolve("does-not-exist"), "unit-skill", jarFile));
    }

    // ---- loadFromExtractedSkills ----

    @Test
    void loadFromExtractedSkillsReturnsEarlyWhenTheHomeIsAbsent(@TempDir Path tempDir) {
        String origHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            List<SkillDefinition> skills = new ArrayList<>();
            provider.loadFromExtractedSkills(skills);

            assertTrue(skills.isEmpty());
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    // ---- loadSkillFromFile / loadSkillContent failure paths ----

    @Test
    void loadSkillFromFileToleratesADirectoryInsteadOfAFile(@TempDir Path tempDir) {
        List<SkillDefinition> skills = new ArrayList<>();

        assertDoesNotThrow(() -> provider.loadSkillFromFile(tempDir, skills));

        assertTrue(skills.isEmpty());
    }

    @Test
    void loadSkillFromFileToleratesAParserFailure(@TempDir Path tempDir) throws Exception {
        Path skillMd = tempDir.resolve("SKILL.md");
        Files.writeString(skillMd, "# Broken\n");
        when(parser.parse(anyString(), any())).thenThrow(new IllegalArgumentException("bad skill"));

        List<SkillDefinition> skills = new ArrayList<>();
        assertDoesNotThrow(() -> provider.loadSkillFromFile(skillMd, skills));

        assertTrue(skills.isEmpty());
    }

    // ---- maybeAugmentBrowserPreview template branch ----

    @Test
    void maybeAugmentBrowserPreviewOmitsTheTemplateInstructionWithoutATemplateDir() {
        SkillDefinition withPreview = new SkillDefinition(
                "s-nodir", "no-dir", "desc", List.of(), List.of(), 5, "prompt", "/home", true, null);

        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(withPreview, "/home");

        assertTrue(augmented.systemPrompt().contains("BROWSER PREVIEW RULES"));
        assertTrue(!augmented.systemPrompt().contains("starter templates"));
    }

    // ---- classpath-enumeration failures ----

    @Test
    void loadFallsBackToTheExtractedSkillHomeWhenTheClasspathYieldsNothing(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        ClassLoader origTccl = Thread.currentThread().getContextClassLoader();
        try {
            System.setProperty("user.home", tempDir.toString());
            Path extracted = tempDir.resolve(".amsha/skills/skillsjars/stranded-skill/SKILL.md");
            Files.createDirectories(extracted.getParent());
            Files.writeString(extracted, "# Stranded\n");
            when(parser.parse(anyString(), any())).thenReturn(def("stranded-skill"));

            List<SkillDefinition> skills;
            // An empty context classloader makes both the index lookup and the classpath scan come up empty,
            // which is the only route into the native-image fallback branch of load().
            try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
                Thread.currentThread().setContextClassLoader(empty);
                skills = provider.load();
            }

            assertEquals("stranded-skill", firstId(skills));
        } finally {
            Thread.currentThread().setContextClassLoader(origTccl);
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void loadToleratesAClasspathThatCannotBeEnumerated(@TempDir Path tempDir) {
        String origHome = System.getProperty("user.home");
        ClassLoader origTccl = Thread.currentThread().getContextClassLoader();
        ClassLoader unenumerable = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("classpath unavailable");
            }
        };
        try {
            // Point user.home at an empty directory so the extracted-home fallback cannot repopulate the list.
            System.setProperty("user.home", tempDir.toString());
            Thread.currentThread().setContextClassLoader(unenumerable);

            List<SkillDefinition> skills = assertDoesNotThrow(provider::load);

            assertTrue(skills.isEmpty());
        } finally {
            Thread.currentThread().setContextClassLoader(origTccl);
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void loadFromExtractedSkillsToleratesAnUnreadableHome(@TempDir Path tempDir) throws Exception {
        assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "requires POSIX permissions to make the skill home unreadable");
        String origHome = System.getProperty("user.home");
        Path home = tempDir.resolve(".amsha/skills/skillsjars");
        Files.createDirectories(home);
        try {
            System.setProperty("user.home", tempDir.toString());
            // The directory still satisfies isDirectory(), so only Files.list() can fail.
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("---------"));

            List<SkillDefinition> skills = new ArrayList<>();
            assertDoesNotThrow(() -> provider.loadFromExtractedSkills(skills));

            assertTrue(skills.isEmpty());
        } finally {
            Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwxrwx"));
            System.setProperty("user.home", origHome);
        }
    }

    // ---- defensive guards: URL shapes the instanceof/protocol checks exist for ----

    @Test
    void loadIndexedSkillIgnoresAJarUrlWhoseConnectionIsNotAJarConnection(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        ClassLoader origTccl = Thread.currentThread().getContextClassLoader();
        try {
            System.setProperty("user.home", tempDir.toString());
            Thread.currentThread().setContextClassLoader(returning(urlWithPlainConnection("jar")));
            List<SkillDefinition> skills = new ArrayList<>();

            assertDoesNotThrow(() -> provider.loadIndexedSkill("META-INF/skills/vendor/odd/SKILL.md", skills));

            assertTrue(skills.isEmpty());
        } finally {
            Thread.currentThread().setContextClassLoader(origTccl);
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void loadIndexedSkillIgnoresAnUnrecognisedResourceProtocol(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        ClassLoader origTccl = Thread.currentThread().getContextClassLoader();
        try {
            System.setProperty("user.home", tempDir.toString());
            // Neither jar: nor file:, so both protocol guards fall through to the native-extraction fallback.
            Thread.currentThread().setContextClassLoader(returning(urlWithPlainConnection("mem")));
            List<SkillDefinition> skills = new ArrayList<>();

            assertDoesNotThrow(() -> provider.loadIndexedSkill("META-INF/skills/vendor/mem/SKILL.md", skills));

            assertTrue(skills.isEmpty());
        } finally {
            Thread.currentThread().setContextClassLoader(origTccl);
            System.setProperty("user.home", origHome);
        }
    }

    @Test
    void scanUrlIgnoresAJarUrlWhoseConnectionIsNotAJarConnection() throws Exception {
        List<SkillDefinition> skills = new ArrayList<>();

        assertDoesNotThrow(() -> provider.scanUrl(urlWithPlainConnection("jar"), skills));

        assertTrue(skills.isEmpty());
    }

    // ---- extraction guards ----

    @Test
    void extractSkillAssetsTreatsAPathWithoutASeparatorAsAnEmptySkillDirectory(@TempDir Path tempDir) throws Exception {
        String origHome = System.getProperty("user.home");
        Path jar = tempDir.resolve("flat.jar");
        writeJar(jar, Map.of("META-INF/skills/x/SKILL.md", "# Flat\n"));
        try {
            System.setProperty("user.home", tempDir.toString());

            try (JarFile jarFile = new JarFile(jar.toFile())) {
                // A path with no '/' yields no skill directory. The guard keeps the extraction going rather than
                // failing on an empty skill name, so everything under the entry prefix still lands in the home.
                assertNotNull(provider.extractSkillAssets(jarFile, "SKILL.md"));
            }
        } finally {
            System.setProperty("user.home", origHome);
        }
    }

    // ---- writeSourceMetadata version resolution: the remaining two sources ----

    @Test
    void writeSourceMetadataPrefersTheManifestBundleVersion(@TempDir Path tempDir) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Bundle-Version", "7.1.0");
        Path jar = tempDir.resolve("bundled.jar");
        writeJar(jar, Map.of("x.txt", "x"), manifest);
        Path target = tempDir.resolve("meta-manifest");
        Files.createDirectories(target);

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            provider.writeSourceMetadata(target, "unit-skill", jarFile);
        }

        assertTrue(Files.readString(target.resolve(".skill-source.json")).contains("\"version\": \"7.1.0\""));
    }

    @Test
    void writeSourceMetadataHandlesAJarWhoseNameHasNoJarSuffix(@TempDir Path tempDir) throws Exception {
        // A jar that is not named *.jar: the suffix strip is skipped and the version keeps its extension.
        Path jar = tempDir.resolve("com.skillsjars.unit-skill-9.9.9.zip");
        writeJar(jar, Map.of("x.txt", "x"));
        Path target = tempDir.resolve("meta-no-suffix");
        Files.createDirectories(target);

        try (JarFile jarFile = new JarFile(jar.toFile())) {
            provider.writeSourceMetadata(target, "unit-skill", jarFile);
        }

        assertTrue(Files.readString(target.resolve(".skill-source.json")).contains("\"version\": \"9.9.9.zip\""));
    }

    // ---- loadSkillContent: blank skill home and nested-heading stripping ----

    @Test
    void loadSkillFromResourceTreatsABlankSkillHomeAsAbsent() {
        when(parser.parse(anyString(), any())).thenReturn(def("unit-skill"));

        List<SkillDefinition> skills = new ArrayList<>();
        provider.loadSkillFromResource("/" + CLASSPATH_SKILL, skills, "");

        assertEquals("unit-skill", firstId(skills));
    }

    @Test
    void loadSkillFromFileKeepsSkippingUnderANestedHeading(@TempDir Path tempDir) throws Exception {
        Path skillHome = tempDir.resolve("nested-home");
        Files.createDirectories(skillHome.resolve("scripts"));
        Path skillMd = skillHome.resolve("SKILL.md");
        Files.writeString(skillMd, "# Prerequisites\nintro\n### Deeper Detail\nkeep me\n");
        when(adaptationManager.isAdaptiveEnabled()).thenReturn(false);
        when(parser.parse(anyString(), any())).thenReturn(def("nested"));

        List<SkillDefinition> skills = new ArrayList<>();
        provider.loadSkillFromFile(skillMd, skills);

        assertEquals("nested", firstId(skills));
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(parser).parse(content.capture(), any());
        // A *deeper* heading does not end a stripped section, so its body stays stripped as well.
        assertFalse(content.getValue().contains("keep me"), "a nested heading must not terminate the skipped section");
    }

    // ---- maybeAugmentBrowserPreview / resolveTemplateDir guards ----

    @Test
    void maybeAugmentBrowserPreviewFallsBackToARelativeOutputDirWithoutASkillHome() {
        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(previewDef(null), null);

        assertTrue(augmented.systemPrompt().contains("write them into `output`"));
    }

    @Test
    void maybeAugmentBrowserPreviewTreatsABlankSkillHomeAsAbsent() {
        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(previewDef(null), "   ");

        assertTrue(augmented.systemPrompt().contains("write them into `output`"));
    }

    @Test
    void maybeAugmentBrowserPreviewIgnoresABlankTemplateDir() {
        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(previewDef("   "), "/home");

        assertFalse(augmented.systemPrompt().contains("starter templates"));
    }

    @Test
    void maybeAugmentBrowserPreviewKeepsAnAbsoluteTemplateDirUnresolved() {
        SkillDefinition augmented = SkillsJarSkillProvider.maybeAugmentBrowserPreview(previewDef("/tpl"), "/home");

        assertTrue(augmented.systemPrompt().contains("`/tpl`"));
    }

    @Test
    void maybeAugmentBrowserPreviewResolvesARelativeTemplateDirOnlyWhenASkillHomeExists() {
        // Null and blank homes both leave the relative template dir untouched.
        assertTrue(SkillsJarSkillProvider.maybeAugmentBrowserPreview(previewDef("tpl"), null)
                .systemPrompt()
                .contains("`tpl`"));
        assertTrue(SkillsJarSkillProvider.maybeAugmentBrowserPreview(previewDef("tpl"), "")
                .systemPrompt()
                .contains("`tpl`"));
    }
}
