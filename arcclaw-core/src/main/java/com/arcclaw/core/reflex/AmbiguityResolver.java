package com.arcclaw.core.reflex;

import java.util.List;
import java.util.Optional;

import com.arcclaw.core.state.AgentCheckpoint;
import com.arcclaw.core.state.AgentCronEntity;

public class AmbiguityResolver {

    private final String actionName;
    private final String ambiguityTitle;

    public AmbiguityResolver(String actionName, String ambiguityTitle) {
        this.actionName = actionName;
        this.ambiguityTitle = ambiguityTitle;
    }

    public String detect(Optional<AgentCheckpoint> latestPending, List<AgentCronEntity> pendingCrons) {
        if (latestPending.isPresent()) {
            return null;
        }
        if (pendingCrons.size() > 1) {
            StringBuilder sb = new StringBuilder("⚠️ ").append(ambiguityTitle).append(". You have multiple tasks waiting:\n\n");
            int idx = 1;
            for (AgentCronEntity c : pendingCrons) {
                sb.append("%d️⃣ `/%s %d` or `/%s %s` - (Background) ".formatted(
                        idx, actionName.toLowerCase(), idx, actionName.toLowerCase(), c.id))
                  .append(c.goal).append("\n");
                idx++;
            }
            sb.append("\nPlease reply with the specific reflex numbered option.");
            return sb.toString();
        }
        return null;
    }

}
