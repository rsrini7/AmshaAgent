package com.amsha.core.config;

import java.util.List;

import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.tool.api.ToolNames;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Externalized governance policy loaded from {@code amsha.policy.*} config.
 * <p>
 * Replaces hardcoded patterns and role lists in {@code DestructiveVocabularyFilter}
 * and {@code ToolGovernanceGuard}.
 */
@ConfigMapping(prefix = "amsha.policy")
public interface PolicyConfig {

    DestructiveVocabulary destructiveVocabulary();

    Governance governance();

    interface DestructiveVocabulary {
        /**
         * Regex patterns that flag a message as destructive.
         * Each pattern is compiled with {@code CASE_INSENSITIVE} flag.
         */
        @WithDefault(
                "(?i)\\b(delete|remove|purge|drop|chmod|truncate|wipe|erase|destroy|archive|rename)\\b,(?i)\\brm\\s*-?\\s*rf\\b")
        List<String> patterns();

        /**
         * Role IDs exempt from the destructive vocabulary filter (e.g., debugger).
         */
        @WithDefault(AgentRoleEnum.DEBUGGER_ID)
        List<String> exemptRoles();

        /**
         * Roles whose primary mission is writing code — exempt because code
         * creation often uses destructive vocabulary.
         */
        @WithDefault(AgentRoleEnum.CODER_ID)
        List<String> sandboxOnlyRoles();

        /**
         * The role to redirect to when destructive vocabulary is detected.
         */
        @WithDefault(AgentRoleEnum.COORDINATOR_ID)
        String overrideRole();
    }

    interface Governance {
        /**
         * Tool names that bypass HITL approval in SUPERVISED mode. `schedule_task` was
         * removed per ADR-schedule-task-hitl-under-supervised: cron creation under
         * supervision is operator-consented; `manage_schedule` stays exempt so
         * list/read/delete management actions remain approval-free.
         */
        @WithDefault(ToolNames.MANAGE_SCHEDULE)
        List<String> hitlExemptTools();

        /**
         * Tool operations that receive a 409 (pause-for-approval) instead of
         * a hard 403 when running in SUPERVISED autonomy level.
         */
        @WithDefault(
                "NETWORK_OUTBOUND,VAULT_ACCESS,EXECUTE_SHELL,WRITE_MEMORY,DELEGATE_AUTONOMOUS,WRITE_FS,SCHEDULE_AUTONOMOUS")
        List<String> supervisedSalvageOperations();
    }
}
