package com.amsha.core.agent.role;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

/**
 * Registry of all agent groups (Lokas) in the Amsha Agent system.
 *
 * <p>Groups organize personas by their mythological residence and complementary
 * expertise, enabling realm-based routing where the coordinator dispatches to a
 * group lead who decides whether to self-handle, consult family, or escalate out.
 *
 * <h3>Built-in Groups:</h3>
 * <pre>
 *   KAILASA (Transformation)     — Lead: Shiva     | Members: Parvati, Ganesha, Kartikeya, Durga, Kali
 *   BRAHMALOKA (Creation/Wisdom) — Lead: Brahma    | Members: Saraswati, Brihaspati
 *   VAIKUNTA (Preservation)      — Lead: Vishnu    | Members: Lakshmi
 *   LOKA-BAHIR (Cross-Realm)     — Lead: Dattatreya | Members: (none — independent synthesizer)
 * </pre>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 *   // Coordinator decides which realm handles a task
 *   Optional<AgentGroup> group = registry.findByLead("debugger");
 *   // → AgentGroup(groupId="kailasa", lead="debugger", members=[...])
 *
 *   // Lead checks if a task should be a family huddle
 *   boolean needsFamily = registry.isGroupMember("optimizer", "debugger");
 *   // → true (Ganesha is in Shiva's group)
 * }</pre>
 */
@ApplicationScoped
public class AgentGroupRegistry {

    private static final Logger log = Logger.getLogger(AgentGroupRegistry.class);

    /**
     * Kailasa — The Abode of Transformation.
     * Destruction, debugging, security, logic, policy enforcement, and cleanup.
     * The most active group with the strongest intra-family bonds.
     */
    public static final AgentGroup KAILASA = new AgentGroup(
            "kailasa",
            "Kailasa — The Abode of Transformation",
            AgentRoleEnum.DEBUGGER_ID,
            List.of(
                    AgentRoleEnum.DEBUGGER_ID, // Shiva — lead
                    AgentRoleEnum.NURTURER_ID, // Parvati — wife
                    AgentRoleEnum.OPTIMIZER_ID, // Ganesha — son
                    AgentRoleEnum.SENTINEL_ID, // Kartikeya — son
                    AgentRoleEnum.PROTECTOR_ID, // Durga — form of Parvati
                    AgentRoleEnum.PURIFIER_ID), // Kali — form of Parvati
            "Debugging, security analysis, complex logic, policy enforcement, "
                    + "input validation, code cleanup, and resource lifecycle management.",
            "If the task involves ONLY debugging → handle directly. "
                    + "If it involves security → consult Kartikeya. "
                    + "If it involves complex logic/recursion → consult Ganesha. "
                    + "If it involves policy/guardrails → consult Durga. "
                    + "If it involves cleanup/lifecycle → consult Kali. "
                    + "If it spans multiple areas → run a family huddle with all relevant members.");

    /**
     * Brahmaloka — The Abode of Creation and Wisdom.
     * Code creation, research, and architectural design.
     */
    public static final AgentGroup BRAHMALOKA = new AgentGroup(
            "brahmaloka",
            "Brahmaloka — The Abode of Creation and Wisdom",
            AgentRoleEnum.CODER_ID,
            List.of(
                    AgentRoleEnum.CODER_ID, // Brahma — lead
                    AgentRoleEnum.RESEARCHER_ID, // Saraswati — knowledge
                    AgentRoleEnum.ARCHITECT_ID), // Brihaspati — design
            "Code creation, feature implementation, research, documentation, "
                    + "system architecture, and technical trade-off analysis.",
            "If the task is pure coding → handle directly. "
                    + "If it needs API/library research → consult Saraswati. "
                    + "If it involves architectural decisions → consult Brihaspati. "
                    + "If it needs research + architecture → run a Brahmaloka huddle.");

    /**
     * Vaikunta — The Abode of Preservation.
     * Orchestration, scheduling, system health, and budget management.
     */
    public static final AgentGroup VAIKUNTA = new AgentGroup(
            "vaikunta",
            "Vaikunta — The Abode of Preservation",
            AgentRoleEnum.COORDINATOR_ID,
            List.of(
                    AgentRoleEnum.COORDINATOR_ID, // Vishnu — lead
                    AgentRoleEnum.HEALER_ID), // Lakshmi — wife
            "Top-level orchestration, task scheduling, session management, "
                    + "error recovery, self-healing, and token budget optimization.",
            "Vishnu handles orchestration directly. "
                    + "Consult Lakshmi for error recovery, self-healing, or budget issues.");

    /**
     * Loka-Bahir — The Realm Beyond (Cross-Realm Synthesizer).
     * Dattatreya operates independently, bridging all realms.
     */
    public static final AgentGroup LOKA_BAHIR = new AgentGroup(
            "loka-bahir",
            "Loka-Bahir — The Realm Beyond",
            AgentRoleEnum.SYNTHESIZER_ID,
            List.of(AgentRoleEnum.SYNTHESIZER_ID),
            "Cross-domain synthesis, conflict resolution between realms, " + "and integration of multi-agent outputs.",
            "Dattatreya operates independently. Consulted when outputs from "
                    + "different realms conflict or need integration.");

    /** All built-in groups in definition order. */
    public static final List<AgentGroup> ALL_GROUPS = List.of(KAILASA, BRAHMALOKA, VAIKUNTA, LOKA_BAHIR);

    /** Fast lookup by group ID. */
    private static final Map<String, AgentGroup> BY_GROUP_ID =
            ALL_GROUPS.stream().collect(Collectors.toUnmodifiableMap(AgentGroup::groupId, g -> g));

    /** Fast lookup by member role ID → which group they belong to. */
    private static final Map<String, AgentGroup> BUILT_IN_BY_MEMBER_ROLE;

    static {
        Map<String, AgentGroup> temp = new HashMap<>();
        for (AgentGroup group : ALL_GROUPS) {
            for (String memberId : group.memberRoleIds()) {
                temp.put(memberId, group);
            }
        }
        BUILT_IN_BY_MEMBER_ROLE = Map.copyOf(temp);
    }

    /** Dynamic groups registered at runtime (e.g., from skills or config). */
    private final ConcurrentHashMap<String, AgentGroup> dynamicGroups = new ConcurrentHashMap<>();

    /**
     * Find a group by its ID.
     */
    public Optional<AgentGroup> findById(String groupId) {
        if (groupId == null) return Optional.empty();
        AgentGroup builtIn = BY_GROUP_ID.get(groupId);
        if (builtIn != null) return Optional.of(builtIn);
        return Optional.ofNullable(dynamicGroups.get(groupId));
    }

    /**
     * Find the group where the given role is the lead.
     */
    public Optional<AgentGroup> findByLead(String leadRoleId) {
        if (leadRoleId == null) return Optional.empty();
        // Check built-in
        Optional<AgentGroup> builtIn =
                ALL_GROUPS.stream().filter(g -> g.isLead(leadRoleId)).findFirst();
        if (builtIn.isPresent()) return builtIn;
        // Check dynamic
        return dynamicGroups.values().stream().filter(g -> g.isLead(leadRoleId)).findFirst();
    }

    /**
     * Find the group that the given role belongs to.
     */
    public Optional<AgentGroup> findByMember(String roleId) {
        if (roleId == null) return Optional.empty();
        AgentGroup builtIn = BUILT_IN_BY_MEMBER_ROLE.get(roleId);
        if (builtIn != null) return Optional.of(builtIn);
        return dynamicGroups.values().stream().filter(g -> g.contains(roleId)).findFirst();
    }

    /**
     * Check if two roles belong to the same group.
     */
    public boolean areInSameGroup(String roleId1, String roleId2) {
        Optional<AgentGroup> g1 = findByMember(roleId1);
        Optional<AgentGroup> g2 = findByMember(roleId2);
        return g1.isPresent()
                && g2.isPresent()
                && g1.get().groupId().equals(g2.get().groupId());
    }

    /**
     * Get all members of the group that roleId belongs to, excluding roleId itself.
     */
    public List<String> getSiblings(String roleId) {
        return findByMember(roleId)
                .map(group -> group.memberRoleIds().stream()
                        .filter(id -> !id.equals(roleId))
                        .toList())
                .orElse(List.of());
    }

    /**
     * Register a dynamic group at runtime (e.g., from skill definitions).
     */
    public void registerDynamic(AgentGroup group) {
        dynamicGroups.put(group.groupId(), group);
        log.infof(
                "[GROUP-REGISTRY] Registered dynamic group: %s (lead=%s, members=%s)",
                group.groupId(), group.leadRoleId(), group.memberRoleIds());
    }

    /**
     * @return all groups (built-in + dynamic)
     */
    public List<AgentGroup> allGroups() {
        List<AgentGroup> all = new ArrayList<>(ALL_GROUPS);
        all.addAll(dynamicGroups.values());
        return all;
    }

    /**
     * Builds a human-readable summary of all groups for prompt injection.
     */
    public String toPromptSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("AGENT GROUPS (LOKAS):\n");
        for (AgentGroup group : allGroups()) {
            sb.append(String.format(
                    "- %s [%s]: Lead=%s, Members=%s\n",
                    group.displayName(), group.groupId(), group.leadRoleId(), group.memberRoleIds()));
        }
        return sb.toString();
    }
}
