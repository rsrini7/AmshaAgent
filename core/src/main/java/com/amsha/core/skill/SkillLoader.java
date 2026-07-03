package com.amsha.core.skill;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import com.amsha.core.agent.AgentSentinels;
import com.amsha.core.agent.role.AgentRoleEnum;
import com.amsha.core.agent.role.CapabilityRegistry;
import com.amsha.core.agent.role.RoleDefinition;
import com.amsha.core.agent.role.RoleRegistry;
import com.amsha.core.agent.role.ToolCapability;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.audit.TelemetryAuditService;
import com.amsha.core.tools.ToolCategory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

/**
 * Orchestrates the loading and compilation of agent skills via {@link SkillProvider} SPI.
 */
@ApplicationScoped
public class SkillLoader {

    private static final Logger LOG = Logger.getLogger(SkillLoader.class);

    @Inject
    Instance<SkillProvider> providers;

    @Inject
    SkillRegistry skillRegistry;

    @Inject
    RoleRegistry roleRegistry;

    @Inject
    TelemetryAuditService telemetryAuditService;

    @Inject
    CapabilityRegistry capabilityRegistry;

    @Inject
    @ConfigProperty(name = "amsha.skills.skillsjars.require-approval-for-sensitive", defaultValue = "true")
    boolean requireApprovalForSensitive;

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Initializing AgentSkills - SPI-based load starting");
        List<String> warnings = reloadAll(false);
        if (!warnings.isEmpty()) {
            LOG.warnf("Startup skill load completed with %d warning(s):", warnings.size());
            warnings.forEach(w -> LOG.warnf("  %s", w));
        }
    }

    public List<String> reloadAll(boolean isRefresh) {
        // Clear old dynamic skill grants from CapabilityRegistry
        for (String roleName : roleRegistry.getDynamicRoles().stream()
                .map(RoleDefinition::canonicalName)
                .toList()) {
            capabilityRegistry.remove(roleName);
        }

        Map<String, CompiledSkillEntry> mergedSkills = new LinkedHashMap<>();
        List<String> allWarnings = new ArrayList<>();

        // Sort by priority (lower = higher priority, processed first)
        // putIfAbsent semantics: first writer wins → highest-priority provider wins on collision
        List<SkillProvider> ordered = providers.stream()
                .sorted(Comparator.comparingInt(SkillProvider::priority))
                .toList();

        for (SkillProvider provider : ordered) {
            LOG.infof("Loading skills from provider: %s (priority=%d)", provider.sourceName(), provider.priority());
            List<SkillDefinition> definitions = provider.load();
            for (SkillDefinition def : definitions) {
                try {
                    allWarnings.addAll(addSafe(mergedSkills, def, provider.sourceName()));
                } catch (Exception e) {
                    String w = "Failed to compile skill '" + def.name() + "' from " + provider.sourceName() + ": "
                            + e.getMessage();
                    LOG.warn(w);
                    allWarnings.add(w);
                }
            }
        }

        List<CompiledSkillEntry> finalList = List.copyOf(mergedSkills.values());
        skillRegistry.reload(finalList);

        for (CompiledSkillEntry entry : finalList) {
            roleRegistry.registerDynamic(entry.definition().name(), entry.roleDefinition());
        }

        LOG.infof("Skill load complete. Total Loaded: %d", finalList.size());

        SecurityEventType eventType = isRefresh ? SecurityEventType.SKILL_REFRESHED : SecurityEventType.SKILL_LOADED;
        telemetryAuditService.record(
                AgentSentinels.SYSTEM_SESSION,
                AgentSentinels.SYSTEM_USER,
                AgentSentinels.SYSTEM_ROLE,
                eventType,
                "Loaded " + finalList.size() + " skills");

        return List.copyOf(allWarnings);
    }

    private List<String> addSafe(Map<String, CompiledSkillEntry> map, SkillDefinition def, String source) {
        List<String> warnings = new ArrayList<>();
        try {
            List<Pattern> compiledPatterns = compilePatterns(def.triggerPatterns());
            CompileToolsResult toolsResult = compileTools(def.name(), def.toolCapabilities());
            warnings.addAll(toolsResult.warnings());

            if (toolsResult.valid().isEmpty()
                    && def.toolCapabilities() != null
                    && !def.toolCapabilities().isEmpty()) {
                throw new IllegalArgumentException(
                        "All declared tool-capabilities are invalid: " + def.toolCapabilities()
                                + ". Valid values: " + validCapabilityNames()
                                + ". Skill rejected to prevent a zombie role.");
            }

            String canonicalRoleName = AgentRoleEnum.SKILL_PREFIX + def.name();
            boolean isThirdParty = "skillsjars".equals(source);
            boolean hasSensitiveOps = toolsResult.valid().stream()
                    .anyMatch(cap -> cap.category() == ToolCategory.COMPUTE
                            || cap.category() == ToolCategory.NETWORK
                            || cap == ToolCapability.DELEGATE_TO_AGENT);
            boolean requiresApproval = isThirdParty && hasSensitiveOps && requireApprovalForSensitive;

            RoleDefinition roleDef = new RoleDefinition(
                    canonicalRoleName,
                    "Skill: " + def.name(),
                    def.description(),
                    def.maxIterations(),
                    requiresApproval,
                    false, // codeCapable
                    toolsResult.valid(),
                    Set.of(def.name().toLowerCase(Locale.ROOT)));

            for (ToolCapability cap : toolsResult.valid()) {
                capabilityRegistry.grant(canonicalRoleName, cap.toolName());
            }

            map.put(def.name(), new CompiledSkillEntry(def, compiledPatterns, roleDef, source));

        } catch (PatternSyntaxException e) {
            String w = "Failed to compile trigger-pattern regex for skill '" + def.name() + "': " + e.getMessage();
            LOG.warnf(w);
            warnings.add(w);
        }
        return warnings;
    }

    private List<Pattern> compilePatterns(List<String> rawPatterns) {
        return rawPatterns.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    private CompileToolsResult compileTools(String skillName, List<String> rawTools) {
        if (rawTools == null || rawTools.isEmpty()) {
            return new CompileToolsResult(Set.of(), List.of());
        }

        List<String> warnings = new ArrayList<>();
        Set<ToolCapability> valid = rawTools.stream()
                .map(str -> {
                    String normalized = str.toUpperCase(Locale.ROOT).replace("_", "");
                    for (ToolCapability cap : ToolCapability.values()) {
                        if (cap.name().replace("_", "").equals(normalized)) {
                            return cap;
                        }
                    }
                    String w = "Unknown ToolCapability '" + str + "' in skill '" + skillName
                            + "' - excluded. Valid values: " + validCapabilityNames();
                    LOG.warnf(w);
                    warnings.add(w);
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());

        return new CompileToolsResult(valid, List.copyOf(warnings));
    }

    private record CompileToolsResult(Set<ToolCapability> valid, List<String> warnings) {}

    private static String validCapabilityNames() {
        return Arrays.stream(ToolCapability.values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
