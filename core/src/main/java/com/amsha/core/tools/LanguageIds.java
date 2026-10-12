package com.amsha.core.tools;

/**
 * Canonical language / interpreter identifiers used by the sandbox and host execution paths.
 * <p>
 * These tokens are load-bearing discriminators: the same string selects the command to run
 * ({@link CodeLanguageDetector#buildCommand}), gates verification
 * ({@code SandboxVerificationGate.isExecutableLanguage}), selects the safety-pattern set
 * ({@code SandboxCodeSafetyScanner}), and normalises user input ({@code RecoveryProcessor}).
 * A rename in one place while another is missed silently routes execution to the wrong branch
 * or to the {@code default} error path, so the vocabulary lives here.
 * <p>
 * The same token doubles as the interpreter / shell executable name (for example
 * {@code "python3"}, {@code "bash"}, {@code "node"}), which is why the shell names
 * {@link #SH} and {@link #ZSH} are included alongside the languages: the sandbox treats
 * {@link #BASH} as a supported language, and the shell detectors match the same strings.
 * <p>
 * Deliberately <strong>not</strong> here: the natural-language phrases that a text heuristic
 * matches against (for example {@code " java "} with surrounding spaces) — those are user
 * text, not identifiers.
 */
public final class LanguageIds {

    private LanguageIds() {}

    /** Java. */
    public static final String JAVA = "java";

    /** Python (canonical; {@link #PYTHON3} is an accepted alias). */
    public static final String PYTHON = "python";

    /** Python 3 interpreter name / alias. */
    public static final String PYTHON3 = "python3";

    /** Go. */
    public static final String GO = "go";

    /** Node.js (canonical; {@link #JAVASCRIPT} and {@link #JS} are accepted aliases). */
    public static final String NODE = "node";

    /** Bash. */
    public static final String BASH = "bash";

    /** JavaScript — an alias of {@link #NODE}. */
    public static final String JAVASCRIPT = "javascript";

    /** {@code js} — an alias of {@link #NODE}. */
    public static final String JS = "js";

    /** POSIX shell — an alias of {@link #BASH}. */
    public static final String SH = "sh";

    /** Zsh shell, recognised by the command-first-token detector. */
    public static final String ZSH = "zsh";
}
