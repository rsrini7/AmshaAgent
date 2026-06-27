package com.amsha.core.agent.role;

import java.util.List;

/**
 * Defines a logical grouping (Loka) of agent personas based on their
 * mythological residence and complementary expertise domains.
 *
 * <p>Groups enable two key behaviors:
 * <ol>
 *   <li><b>Realm-Based Routing</b> — the coordinator delegates to the group lead,
 *       who decides whether to handle the task, consult family members, or escalate out.</li>
 *   <li><b>Intra-Group Consultation</b> — the lead can run a "family huddle" where
 *       multiple members analyze the task in parallel before the lead synthesizes
 *       a unified response.</li>
 * </ol>
 *
 * @param groupId          Unique identifier (e.g., "kailasa", "vaikunta", "brahmaloka")
 * @param displayName      Human-readable name (e.g., "Kailasa — The Abode of Transformation")
 * @param leadRoleId       The group lead's role ID — first point of contact for the group
 * @param memberRoleIds    All member role IDs including the lead
 * @param domainDescription What this group handles — used in coordinator routing prompts
 * @param consultationHint Prompt hint for the lead on when to consult members
 */
public record AgentGroup(
        String groupId,
        String displayName,
        String leadRoleId,
        List<String> memberRoleIds,
        String domainDescription,
        String consultationHint) {

    public AgentGroup {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId must not be null or blank");
        }
        if (leadRoleId == null || leadRoleId.isBlank()) {
            throw new IllegalArgumentException("leadRoleId must not be null or blank");
        }
        memberRoleIds = memberRoleIds == null ? List.of() : List.copyOf(memberRoleIds);
        if (!memberRoleIds.contains(leadRoleId)) {
            throw new IllegalArgumentException("leadRoleId '" + leadRoleId + "' must be a member of the group");
        }
    }

    /**
     * @return true if the given role ID is a member of this group
     */
    public boolean contains(String roleId) {
        return memberRoleIds.contains(roleId);
    }

    /**
     * @return true if the given role ID is the lead of this group
     */
    public boolean isLead(String roleId) {
        return leadRoleId.equals(roleId);
    }

    /**
     * @return member role IDs excluding the lead — the "family" that can be consulted
     */
    public List<String> consultableMembers() {
        return memberRoleIds.stream().filter(id -> !id.equals(leadRoleId)).toList();
    }
}
