package com.arcclaw.core.security;

import java.util.List;

import com.arcclaw.core.config.PolicyConfig;

/**
 * Provides a default PolicyConfig for tests that don't run in a Quarkus container.
 */
public final class PolicyConfigTestHelper {

    private PolicyConfigTestHelper() {}

    public static PolicyConfig defaultConfig() {
        return new PolicyConfig() {
            @Override public DestructiveVocabulary destructiveVocabulary() {
                return new DestructiveVocabulary() {
                    @Override public List<String> patterns() {
                        return List.of(
                            "(?i)\\b(delete|remove|purge|drop|chmod|truncate|wipe|erase|destroy|archive|rename)\\b",
                            "(?i)\\brm\\s*-?\\s*rf\\b"
                        );
                    }
                    @Override public List<String> exemptRoles() { return List.of("debugger"); }
                    @Override public List<String> sandboxOnlyRoles() { return List.of("coder"); }
                    @Override public String overrideRole() { return "coordinator"; }
                };
            }
            @Override public Governance governance() {
                return new Governance() {
                    @Override public List<String> hitlExemptTools() { return List.of("schedule_task", "manage_schedule"); }
                    @Override public List<String> supervisedSalvageOperations() {
                        return List.of("NETWORK_OUTBOUND", "VAULT_ACCESS", "EXECUTE_SHELL",
                                "WRITE_MEMORY", "DELEGATE_AUTONOMOUS", "WRITE_FS");
                    }
                };
            }
        };
    }
}
