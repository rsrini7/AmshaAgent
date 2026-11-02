package com.amsha.sandbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class FakePodmanHelper {

    private FakePodmanHelper() {}

    static Path createFakePodman(Path dir, String prefix, String body) throws IOException {
        if (File.separatorChar == '\\') {
            Path exe = dir.resolve(prefix + System.nanoTime() + ".cmd");
            Files.writeString(exe, toWindowsBatch(body), StandardCharsets.UTF_8);
            return exe;
        }
        Path exe = dir.resolve(prefix + System.nanoTime() + ".sh");
        Files.writeString(exe, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertTrue(exe.toFile().setExecutable(true), "fake podman must be executable");
        return exe;
    }

    private static String toWindowsBatch(String body) {
        StringBuilder sb = new StringBuilder("@echo off\r\n");
        body.lines().forEach(line -> {
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.startsWith("#")
                    || trimmed.equals("esac")
                    || trimmed.equals("case \"$1\" in")) {
                return;
            }
            if (trimmed.startsWith("sleep ")) {
                int secs = Integer.parseInt(trimmed.substring(6).trim());
                sb.append("ping -n ").append(secs + 1).append(" 127.0.0.1 >nul\r\n");
            } else if (trimmed.startsWith("exit ")) {
                sb.append("exit /b ")
                        .append(trimmed.substring(5).replace(";", "").trim())
                        .append("\r\n");
            } else if (trimmed.startsWith("info)")) {
                sb.append("if \"%1\"==\"info\" exit /b 0\r\n");
            } else if (trimmed.startsWith("image)")) {
                int code = trimmed.contains("exit 1") ? 1 : 0;
                sb.append("if \"%1\"==\"image\" exit /b ").append(code).append("\r\n");
            } else if (trimmed.startsWith("run)")) {
                sb.append("if \"%1\"==\"run\" (\r\n  echo tool output\r\n  exit /b 0\r\n)\r\n");
            } else if (trimmed.startsWith("stop)")) {
                sb.append("if \"%1\"==\"stop\" exit /b 0\r\n");
            } else if (trimmed.startsWith("echo ")) {
                String msg = trimmed.substring(5).replace("'", "").replace("\"", "");
                int exitIdx = msg.indexOf("; exit ");
                if (exitIdx >= 0) {
                    sb.append("echo ").append(msg.substring(0, exitIdx).trim()).append("\r\n");
                    sb.append("exit /b ")
                            .append(msg.substring(exitIdx + 7).trim())
                            .append("\r\n");
                } else {
                    sb.append("echo ").append(msg).append("\r\n");
                }
            }
        });
        return sb.toString();
    }
}
