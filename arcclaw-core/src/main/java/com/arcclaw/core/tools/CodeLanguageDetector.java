package com.arcclaw.core.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeLanguageDetector {

    private static final Pattern PUBLIC_TYPE_PATTERN =
            Pattern.compile("(?:^|\\n)\\s*public\\s+(?:class|record|@?interface|enum)\\s+(\\w+)", Pattern.MULTILINE);

    private static final Pattern ANY_TYPE_PATTERN =
            Pattern.compile("(?:public\\s+)?(?:class|record|@?interface|enum)\\s+(\\w+)", Pattern.MULTILINE);

    private static final Pattern FS_ILLEGAL_CHARS = Pattern.compile("[:<>\"\\\\|?*]");

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final Optional<String> configuredPythonExe;
    private final Optional<String> configuredJavaExe;
    private final Optional<String> configuredGoExe;

    public CodeLanguageDetector(Optional<String> pythonExe, Optional<String> javaExe, Optional<String> goExe) {
        this.configuredPythonExe = pythonExe;
        this.configuredJavaExe = javaExe;
        this.configuredGoExe = goExe;
    }

    public boolean containsTypeDeclaration(String code) {
        return ANY_TYPE_PATTERN.matcher(code).find();
    }

    public String extractJavaClassName(String code) {
        Matcher m = PUBLIC_TYPE_PATTERN.matcher(code);
        if (m.find()) return m.group(1);
        m = ANY_TYPE_PATTERN.matcher(code);
        if (m.find()) return m.group(1);
        return "Main";
    }

    public List<String> buildCommand(String lang, String code) {
        return switch (lang) {
            case "python", "python3" -> List.of(resolvePythonExecutable(), "-c", code);
            case "bash" -> List.of("bash", "-c", code);
            case "node" -> List.of("node", "-e", code);
            default ->
                throw new IllegalArgumentException(
                        "Unsupported language '" + lang + "'. Only Java, Python, Go, Node, and Bash are supported.");
        };
    }

    public String resolvePythonExecutable() {
        return resolveExecutable(IS_WINDOWS ? "python" : "python3", configuredPythonExe);
    }

    public String resolveJavaExecutable() {
        if (IS_WINDOWS && configuredJavaExe.isEmpty()) {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                Path javaExe = Path.of(javaHome, "bin", "java.exe");
                if (Files.exists(javaExe)) return javaExe.toString();
            }
        }
        return resolveExecutable("java", configuredJavaExe);
    }

    public String resolveGoExecutable() {
        return resolveExecutable("go", configuredGoExe);
    }

    private static final int MAX_FS_NAME_LENGTH = 200;

    public String toFsName(String sessionId) {
        String sanitized = FS_ILLEGAL_CHARS.matcher(sessionId).replaceAll("_");
        if (sanitized.length() <= MAX_FS_NAME_LENGTH) return sanitized;
        // Truncate and append hash to keep uniqueness while staying under limit
        String hash = sha256Prefix(sanitized, 12);
        return sanitized.substring(0, MAX_FS_NAME_LENGTH - 13) + "_" + hash;
    }

    private static String sha256Prefix(String input, int prefixLen) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
                if (sb.length() >= prefixLen) break;
            }
            return sb.substring(0, prefixLen);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String resolveExecutable(String command, Optional<String> config) {
        if (config.isPresent() && !config.get().isBlank()) return config.get();
        return command;
    }
}
