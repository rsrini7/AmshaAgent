package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import com.arcclaw.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class CoderRoutingProvider implements IntentRoutingProvider {
    private static final Pattern CODER_FORCE_PATTERN =
        Pattern.compile(
            "(?i)^\\s*(write|create|generate|implement|code|build|make)\\s+" +
            ".*(program|function|method|class|algorithm|code|script|snippet|" +
            "fibonacci|fib|sort|search|binary|linked.?list|tree|graph|" +
            "hello.?world|factorial|prime|palindrome|anagram)",
            Pattern.CASE_INSENSITIVE
        );

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(CODER_FORCE_PATTERN);
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "If the user is asking to write, build, or implement NEW software logic or code, route to 'brahma/coder'."
        );
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.of(AgentRoleEnum.CODER.toScopedName());
    }

    @Override
    public int priority() {
        return 20;
    }
}
