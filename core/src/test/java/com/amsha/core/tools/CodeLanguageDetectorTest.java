package com.amsha.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CodeLanguageDetectorTest {

    private final CodeLanguageDetector detector =
            new CodeLanguageDetector(Optional.empty(), Optional.empty(), Optional.empty());

    @Test
    void testContainsTypeDeclaration() {
        assertTrue(detector.containsTypeDeclaration("public class Foo {}"));
        assertTrue(detector.containsTypeDeclaration("record Bar() {}"));
        assertTrue(detector.containsTypeDeclaration("interface Baz {}"));
        assertTrue(detector.containsTypeDeclaration("enum Qux { A }"));
        assertFalse(detector.containsTypeDeclaration("int x = 5;"));
    }

    @Test
    void testExtractJavaClassName() {
        assertEquals("Foo", detector.extractJavaClassName("public class Foo {}"));
        assertEquals("Bar", detector.extractJavaClassName("class Bar {}"));
        assertEquals("Main", detector.extractJavaClassName("int x = 5;"));
    }

    @Test
    void testBuildCommand() {
        List<String> pyCmd = detector.buildCommand("python", "print('hi')");
        assertEquals("-c", pyCmd.get(1));
        assertEquals("print('hi')", pyCmd.get(2));

        List<String> bashCmd = detector.buildCommand("bash", "echo 1");
        assertEquals(List.of("bash", "-c", "echo 1"), bashCmd);

        List<String> nodeCmd = detector.buildCommand("node", "console.log(1)");
        assertEquals(List.of("node", "-e", "console.log(1)"), nodeCmd);

        assertThrows(IllegalArgumentException.class, () -> detector.buildCommand("ruby", "puts 1"));
    }

    @Test
    void testResolveExecutablesWithConfig() {
        CodeLanguageDetector custom = new CodeLanguageDetector(
                Optional.of("/usr/bin/python3"), Optional.of("/usr/bin/java"), Optional.of("/usr/bin/go"));

        assertEquals("/usr/bin/python3", custom.resolvePythonExecutable());
        assertEquals("/usr/bin/java", custom.resolveJavaExecutable());
        assertEquals("/usr/bin/go", custom.resolveGoExecutable());
    }

    @Test
    void testToFsName() {
        assertEquals("valid_name_123_", detector.toFsName("valid:name<123>"));
        String longId = "a".repeat(250);
        String fsName = detector.toFsName(longId);
        assertTrue(fsName.length() <= 200);
        assertTrue(fsName.contains("_"));
    }
}
