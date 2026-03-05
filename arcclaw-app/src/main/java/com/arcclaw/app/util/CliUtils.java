package com.arcclaw.app.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CliUtils {

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

    public static void validateRequiredEnvVars() {
        String[] required = {
            "GROQ_API_KEY",
            "ARCCLAW_VAULT_PEPPER",
            "ARCCLAW_AUDIT_HMAC_KEY"
        };

        List<String> missing = new ArrayList<>();
        for (String key : required) {
            String val = System.getProperty(key, System.getenv(key));
            if (val == null || val.isBlank()) {
                missing.add(key);
            }
        }

        if (!missing.isEmpty()) {
            System.err.println("[ArcClaw] ❌ Missing required configuration:");
            missing.forEach(k -> System.err.println("   - " + k));
            System.err.println("\nSet these in your .env file or as environment variables.");
            System.err.println("See .env.example for reference.");
            System.exit(1);
        }
    }

}
