package com.arcclaw.core.agent.role;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Type-safe enumeration of built-in agent personas in the ArcClaw system.
 * Maps human-readable deity names to canonical system role IDs.
 */
public enum AgentPersona {
    BRIHASPATI("architect", "Brihaspati (The Architect)", Set.of("brihaspati", "brahaspati")),
    VISHNU("coordinator", "Vishnu (The Coordinator)", Set.of("vishnu")),
    SARASWATI("researcher", "Saraswati (The Researcher)", Set.of("saraswati")),
    BRAHMA("coder", "Brahma (The Creator)", Set.of("brahma")),
    SHIVA("debugger", "Shiva (The Debugger)", Set.of("shiva")),
    DATTATREYA("synthesizer", "Dattatreya (The Synthesizer)", Set.of("dattatreya", "datta")),
    GANESHA("optimizer", "Ganesha (The Obstacle Remover)", Set.of("ganesha", "ganesh")),
    KARTIKEYA("sentinel", "Kartikeya (The Sentinel)", Set.of("kartikeya", "murugan", "skanda")),
    LAKSHMI("healer", "Lakshmi (The Healer)", Set.of("lakshmi")),
    DURGA("protector", "Durga (The Protector)", Set.of("durga")),
    KALI("purifier", "Kali (The Purifier)", Set.of("kali")),
    PARVATI("nurturer", "Parvati (The Nurturer)", Set.of("parvati", "uma"));

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
