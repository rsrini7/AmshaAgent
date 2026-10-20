package com.amsha.core.agent.role;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Type-safe enumeration of built-in agent personas in the Amsha Agent system.
 * Maps human-readable deity names to canonical system role IDs.
 */
public enum AgentPersona {
    BRIHASPATI(AgentRoleEnum.ARCHITECT_ID, "Brihaspati (The Architect)", Set.of("brihaspati", "brahaspati")),
    VISHNU(AgentRoleEnum.COORDINATOR_ID, "Vishnu (The Coordinator)", Set.of("vishnu")),
    SARASWATI(AgentRoleEnum.RESEARCHER_ID, "Saraswati (The Researcher)", Set.of("saraswati")),
    BRAHMA(AgentRoleEnum.CODER_ID, "Brahma (The Creator)", Set.of("brahma")),
    SHIVA(AgentRoleEnum.DEBUGGER_ID, "Shiva (The Debugger)", Set.of("shiva")),
    DATTATREYA(AgentRoleEnum.SYNTHESIZER_ID, "Dattatreya (The Synthesizer)", Set.of("dattatreya", "datta")),
    GANESHA(AgentRoleEnum.OPTIMIZER_ID, "Ganesha (The Obstacle Remover)", Set.of("ganesha", "ganesh")),
    KARTIKEYA(AgentRoleEnum.SENTINEL_ID, "Kartikeya (The Sentinel)", Set.of("kartikeya", "murugan", "skanda")),
    LAKSHMI(AgentRoleEnum.HEALER_ID, "Lakshmi (The Healer)", Set.of("lakshmi")),
    DURGA(AgentRoleEnum.PROTECTOR_ID, "Durga (The Protector)", Set.of("durga")),
    KALI(AgentRoleEnum.PURIFIER_ID, "Kali (The Purifier)", Set.of("kali")),
    PARVATI(AgentRoleEnum.NURTURER_ID, "Parvati (The Nurturer)", Set.of("parvati", "uma"));

    private final String roleId;
    private final String displayName;
    private final Set<String> aliases;

    AgentPersona(String roleId, String displayName, Set<String> aliases) {
        this.roleId = roleId;
        this.displayName = displayName;
        this.aliases = aliases;
    }

    public String roleId() {
        return roleId;
    }

    public String displayName() {
        return displayName;
    }

    public Set<String> aliases() {
        return aliases;
    }

    /**
     * Resolves a persona from a string name, role ID, or alias.
     */
    public static Optional<AgentPersona> fromString(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (AgentPersona p : values()) {
            if (p.name().toLowerCase(Locale.ROOT).equals(n)
                    || p.roleId.equals(n)
                    || p.aliases.stream().anyMatch(a -> a.equals(n))) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
