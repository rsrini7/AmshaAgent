package com.arcclaw.core.reflex;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.arcclaw.core.domain.model.AgentCheckpoint;
import com.arcclaw.core.domain.model.AgentCron;

public class AmbiguityResolver {

    private final String actionName;
    private final String ambiguityTitle;

    public AmbiguityResolver(String actionName, String ambiguityTitle) {
        this.actionName = actionName;
        this.ambiguityTitle = ambiguityTitle;
    }

    public String detect(Optional<AgentCheckpoint> latestPending, List<AgentCron> pendingCrons) {
        if (latestPending.isPresent()) {
            return null;
        }
        if (pendingCrons.size() > 1) {
            StringBuilder sb =
                    new StringBuilder("⚠️ ").append(ambiguityTitle).append(". You have multiple tasks waiting:\n\n");
            int idx = 1;
            for (AgentCron c : pendingCrons) {
                sb.append("%d️⃣ `/%s %d` or `/%s %s` - (Background) "
                                .formatted(
                                        idx,
                                        actionName.toLowerCase(Locale.ROOT),
                                        idx,
                                        actionName.toLowerCase(Locale.ROOT),
                                        c.getId()))
                        .append(c.getGoal())
                        .append("\n");
                idx++;
            }
            sb.append("\nPlease reply with the specific reflex numbered option.");
            return sb.toString();
        }
        return null;
    }
}
