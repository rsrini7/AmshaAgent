package com.amsha.core.security;

import java.util.List;

import com.amsha.core.config.PolicyConfig;
import com.amsha.tool.api.ToolCapability;

/**
 * Provides a default PolicyConfig for tests that don't run in a Quarkus container.
 */
public final class PolicyConfigTestHelper {

    private PolicyConfigTestHelper() {}

    public static PolicyConfig defaultConfig() {
        return new PolicyConfig() {
            @Override
            public DestructiveVocabulary destructiveVocabulary() {
                return new DestructiveVocabulary() {
                    @Override
                    public List<String> patterns() {
                        return List.of(
                                "(?i)\\b(delete|remove|purge|drop|chmod|truncate|wipe|erase|destroy|archive|rename)\\b",
                                "(?i)\\brm\\s*-?\\s*rf\\b");
                    }

                    @Override
                    public List<String> exemptRoles() {
                        return List.of("debugger");
                    }

                    @Override
                    public List<String> sandboxOnlyRoles() {
                        return List.of("coder");
                    }

                    @Override
                    public String overrideRole() {
                        return "coordinator";
                    }
                };
            }

            @Override
            public Governance governance() {
                return new Governance() {
                    @Override
                    public List<String> hitlExemptTools() {
                        // ADR-schedule-task-hitl-under-supervised: only manage_schedule stays exempt.
                        return List.of(ToolCapability.MANAGE_SCHEDULE.toolName());
                    }

                    @Override
                    public List<String> supervisedSalvageOperations() {
                        return List.of(
                                "NETWORK_OUTBOUND",
                                "VAULT_ACCESS",
                                "EXECUTE_SHELL",
                                "WRITE_MEMORY",
                                "DELEGATE_AUTONOMOUS",
                                "WRITE_FS",
                                "SCHEDULE_AUTONOMOUS");
                    }
                };
            }
        };
    }
}
