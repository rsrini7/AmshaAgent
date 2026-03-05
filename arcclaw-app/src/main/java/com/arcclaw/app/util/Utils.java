package com.arcclaw.app.util;

import java.nio.file.Files;
import java.nio.file.Path;

public class Utils {

    public static void loadDotEnv() {
        Path envFile = Path.of(".env");
        if (!Files.exists(envFile)) return;
        try {
            Files.readAllLines(envFile).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .forEach(line -> {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim()
                            // Normalize Windows backslashes to forward slashes
                            .replace("\\", "/");
                        System.setProperty(key, value);
                    }
                });
            System.out.println("[ArcClaw] Loaded .env from: " + envFile.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ArcClaw] Could not read .env: " + e.getMessage());
        }
    }

}
