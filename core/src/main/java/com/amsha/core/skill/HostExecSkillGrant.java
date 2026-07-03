package com.amsha.core.skill;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.List;

import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.ToolCapability;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Startup bean that grants the {@code host_exec} tool capability to skill roles
 * configured via {@code amsha.tools.host-exec.skills}.
 * <p>
 * Default config grants {@code host_exec} to the {@code jdb-debugger} skill so
 * it can run JDB directly on the host (bypassing the Podman sandbox where JDB
 * cannot attach to host JVMs).
 * <p>
 * Note: {@code host_exec} is always HITL-approved because it requires
 * {@code EXECUTE_SHELL}; this grant only adds the tool to the skill's
 * permitted-tools set.
 */
@ApplicationScoped
public class HostExecSkillGrant {

    private static final Logger log = Logger.getLogger(HostExecSkillGrant.class);

    @ConfigProperty(name = "amsha.tools.host-exec.skills", defaultValue = "jdb-debugger")
    List<String> hostExecSkills;

    @Inject
    CapabilityRegistry capabilityRegistry;

    // Priority must be ABOVE the default CDI observer priority (2500) so this grant
    // runs AFTER SkillLoader (which uses default priority 2500). Otherwise a hot-reload
    // via SkillLoader.reloadAll() would remove the role before we re-grant host_exec.
    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 600) StartupEvent ev) {
        if (hostExecSkills == null || hostExecSkills.isEmpty()) {
            log.info("[HostExec] No host_exec skills configured (amsha.tools.host-exec.skills is empty)");
            return;
        }

        String toolName = ToolCapability.HOST_EXEC.toolName();
        for (String skillName : hostExecSkills) {
            if (skillName == null) {
                continue;
            }
            String trimmed = skillName.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String roleId = "skill:" + trimmed;
            capabilityRegistry.grant(roleId, toolName);
            log.infof("[HostExec] Granted host_exec to role=%s", roleId);
        }
    }
}
