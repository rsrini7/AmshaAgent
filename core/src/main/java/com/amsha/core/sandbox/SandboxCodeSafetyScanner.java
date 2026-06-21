package com.amsha.core.sandbox;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scans sandbox code for destructive or dangerous patterns before execution.
 * Applied regardless of isolation strategy - direct execution is especially vulnerable.
 *
 * Architecture Invariants §8.5: Sandbox tools must default to isolated execution.
 * When isolation is opted out (DIRECT mode), this scanner is the last safety line.
 *
 * Pattern structure:
 *  - UNIVERSAL_PATTERNS : fire for ALL languages regardless of lang parameter.
 *  - LANG_PATTERNS      : fire only when lang matches - prevents cross-language false positives.
 */
public final class SandboxCodeSafetyScanner {

    private SandboxCodeSafetyScanner() {}

    // -------------------------------------------------------------------------
    // Universal - dangerous in every language context
    // -------------------------------------------------------------------------

    private static final List<Pattern> UNIVERSAL_PATTERNS = List.of(

            // Filesystem destruction - shell
            pattern("rm\\s+(-[^ ]*r[^ ]*)"), // rm -rf, rm -r, rm --recursive, rm -Rf etc.
            pattern("rmdir\\s+/[sS]"), // Windows rmdir /s
            pattern("rd\\s+/[sS]"), // Windows rd /s

            // Windows PowerShell / cmd destruction
            pattern("Remove-Item.*-Recurse"),
            pattern("ri\\s+.*-Recurse"),
            pattern("Format-Volume"),

            // Dangerous device writes
            pattern("dd\\s+if="), // dd if=/dev/zero of=/dev/sda
            pattern("mkfs"),
            pattern(">\\s*/dev/"), // redirect to device file

            // Supply-chain injection
            pattern("curl.*\\|\\s*(ba)?sh"),
            pattern("wget.*\\|\\s*(ba)?sh"),
            pattern("curl.*\\|\\s*python"),

            // Fork bomb (bash)
            pattern(":\\(\\)\\s*\\{"),

            // Privilege escalation
            pattern("sudo\\s+rm"),
            pattern("sudo\\s+dd"),
            pattern("chmod\\s+[0-7]*7[0-7][0-7]\\s+/"), // chmod 777 /
            pattern("chown\\s+-R.*\\s+/"));

    // -------------------------------------------------------------------------
    // Language-scoped - only fire when lang matches to avoid false positives
    // e.g. exec.Command must not block node's child_process.exec
    // -------------------------------------------------------------------------

    private static final List<Pattern> NODE_PATTERNS = List.of(
            pattern("child_process\\.exec"),
            pattern("child_process\\.execSync"),
            pattern("child_process\\.spawn"),
            pattern("child_process\\.execFile"),
            pattern("child_process\\.fork"),
            pattern("fs\\.rmSync"),
            pattern("fs\\.rm\\s*\\("),
            pattern("fs\\.unlinkSync"),
            pattern("shelljs"),
            pattern("execa"));

    private static final Map<String, List<Pattern>> LANG_PATTERNS = Map.of(
            "python",
                    List.of(
                            pattern("shutil\\.rmtree"),
                            pattern("os\\.remove\\s*\\("),
                            pattern("os\\.unlink\\s*\\("),
                            pattern("pathlib.*\\.unlink\\s*\\("),
                            pattern("pathlib.*\\.rmdir\\s*\\("),
                            pattern("os\\.system\\s*\\("),
                            pattern("subprocess\\.(run|call|Popen|check_output)")),
            "python3",
                    List.of(
                            pattern("shutil\\.rmtree"),
                            pattern("os\\.remove\\s*\\("),
                            pattern("os\\.unlink\\s*\\("),
                            pattern("pathlib.*\\.unlink\\s*\\("),
                            pattern("pathlib.*\\.rmdir\\s*\\("),
                            pattern("os\\.system\\s*\\("),
                            pattern("subprocess\\.(run|call|Popen|check_output)")),
            "java",
                    List.of(
                            pattern("Runtime\\.getRuntime\\(\\)\\.exec"),
                            pattern("new\\s+ProcessBuilder"),
                            pattern("Files\\.delete\\s*\\("),
                            pattern("FileUtils\\.delete(Directory|Recursive|Force)?")),
            "go", List.of(pattern("exec\\.Command"), pattern("os\\.Remove(All)?\\s*\\(")),
            "node", NODE_PATTERNS,
            "javascript", NODE_PATTERNS);

    /**
     * Returns the first blocked pattern found in the code, or null if clean.
     *
     * @param lang  language identifier (python, python3, bash, node, java, go)
     * @param code  source code to scan
     * @return      the regex string of the matched pattern, or null
     */
    public static String findViolation(String lang, String code) {
        if (code == null || code.isBlank()) return null;

        for (Pattern p : UNIVERSAL_PATTERNS) {
            if (p.matcher(code).find()) return p.pattern();
        }

        String normalizedLang = lang != null ? lang.toLowerCase(Locale.ROOT) : "";
        for (Pattern p : LANG_PATTERNS.getOrDefault(normalizedLang, List.of())) {
            if (p.matcher(code).find()) return p.pattern();
        }

        return null;
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}
