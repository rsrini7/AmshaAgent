package com.amsha.core.agent.reasoning.prompt;

public final class PromptConstants {

    private PromptConstants() {}

    public static final String ROLE_CORE = "core";

    public static final String KEY_PERSONA_IDENTITY = "persona_identity";
    public static final String KEY_ROLE_TEMPLATE = "role_template";
    public static final String KEY_SECURITY_RULES = "security_rules";
    public static final String KEY_COMPACT_SECURITY_RULES = "compact_security_rules";
    public static final String KEY_EXECUTION_BOUNDARY = "execution_boundary";
    public static final String KEY_BROWSER_RETRY = "browser_retry";
    public static final String KEY_CONTAMINATION_GUARD = "contamination_guard";
    public static final String KEY_ARCHITECTURE = "architecture";
    public static final String KEY_DELEGATE_RULES = "delegate_rules";
    public static final String KEY_GITHUB_RULES = "github_rules";
    public static final String KEY_SANDBOX_RULES = "sandbox_rules";
    public static final String KEY_SAFETY_RULES = "safety_rules";
    public static final String KEY_COMPACTION_SUMMARY = "compaction_summary";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public static final int DEFAULT_VERSION = 1;
    public static final int DEFAULT_ROLLOUT = 100;
}
