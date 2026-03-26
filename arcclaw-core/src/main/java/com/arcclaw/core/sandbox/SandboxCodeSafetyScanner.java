package com.arcclaw.core.sandbox;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans sandbox code for destructive or dangerous patterns before execution.
 * Applied regardless of isolation strategy — direct execution is especially vulnerable.
 *
 * Architecture Invariants §8.5: Sandbox tools must default to isolated execution.
 * When isolation is opted out (DIRECT mode), this scanner is the last safety line.
 */
public final class SandboxCodeSafetyScanner {

    private SandboxCodeSafetyScanner() {}

    // -------------------------------------------------------------------------
    // Cross-language dangerous patterns
    // -------------------------------------------------------------------------

    private static final List<Pattern> BLOCKED = List.of(

        // Filesystem destruction — Python
        pattern("shutil\\.rmtree"),
        pattern("os\\.remove\\s*\\("),
        pattern("os\\.unlink\\s*\\("),
        pattern("pathlib.*\\.unlink\\s*\\("),
        pattern("pathlib.*\\.rmdir\\s*\\("),

        // Shell escalation FROM Python (allows bypassing the language check)
        pattern("os\\.system\\s*\\("),
        pattern("subprocess\\.(run|call|Popen|check_output)"),

        // Filesystem destruction — bash / sh
        pattern("rm\\s+(-[^ ]*r[^ ]*|-r)"),        // rm -rf, rm -r, rm -Rf, etc.
        pattern("rmdir\\s+/[sS]"),                  // Windows rmdir /s
        pattern("rd\\s+/[sS]"),                     // Windows rd /s

        // Windows PowerShell / cmd destruction
        pattern("Remove-Item.*-Recurse"),
        pattern("ri\\s+.*-Recurse"),
        pattern("Format-Volume"),

        // Dangerous redirects / overwrites
        pattern("dd\\s+if="),                       // dd if=/dev/zero of=/dev/sda
        pattern("mkfs"),
        pattern(">\\s*/dev/"),                      // redirect to device

        // Supply-chain injection
        pattern("curl.*\\|\\s*(ba)?sh"),
        pattern("wget.*\\|\\s*(ba)?sh"),
        pattern("curl.*\\|\\s*python"),

        // Fork bomb
        pattern(":\\(\\)\\s*\\{"),

        // Privilege escalation
        pattern("sudo\\s+rm"),
        pattern("sudo\\s+dd"),
        pattern("chmod\\s+[0-7]*7[0-7][0-7]\\s+/"),  // chmod 777 /
        pattern("chown\\s+-R.*\\s+/")
    );

    /**
     * Returns the first blocked pattern description found in the code,
     * or null if the code is clean.
     */
    public static String findViolation(String language, String code) {
        if (code == null || code.isBlank()) return null;

        for (Pattern p : BLOCKED) {
            if (p.matcher(code).find()) {
                return "Blocked pattern detected: [" + p.pattern() + "]. " +
                       "This operation is not permitted in sandbox execution.";
            }
        }
        return null;
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}