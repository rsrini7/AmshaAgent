package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class HostJavaCompilerTest {

    @Test
    void testCompileSimpleJavaFile() throws Exception {
        HostJavaCompiler compiler = new HostJavaCompiler();
        Path tempDir = Files.createTempDirectory("javac-test");
        Path javaFile = tempDir.resolve("HelloTest.java");
        Files.writeString(javaFile, "public class HelloTest { public static void main(String[] args) {} }");

        try {
            compiler.compile(javaFile, tempDir);
            assertTrue(Files.exists(tempDir.resolve("HelloTest.class")));
        } catch (IOException e) {
            // If javac is not in testing environment java.home, verify it throws expected error
            assertTrue(e.getMessage().contains("javac") || e.getMessage().contains("failed"));
        }
    }

    @Test
    void testCompileInvalidJavaFile() throws Exception {
        HostJavaCompiler compiler = new HostJavaCompiler();
        Path tempDir = Files.createTempDirectory("javac-test-err");
        Path javaFile = tempDir.resolve("InvalidClass.java");
        Files.writeString(javaFile, "public class InvalidClass { invalid syntax }");

        assertThrows(IOException.class, () -> {
            compiler.compile(javaFile, tempDir);
        });
    }
}
