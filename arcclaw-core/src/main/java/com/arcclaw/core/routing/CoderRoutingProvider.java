package com.arcclaw.core.routing;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class CoderRoutingProvider implements IntentRoutingProvider {
    private static final Pattern CODER_FORCE_PATTERN =
        Pattern.compile(
            "(?i)^\\s*(write|create|generate|implement|code|build|make|execute|run|share)\\s+" +
            ".*(program|function|method|class|algorithm|code|script|snippet|result|" +
            "fibonacci|fib|sort|search|binary|linked.?list|tree|graph|" +
            "hello.?world|factorial|prime|palindrome|anagram)",
            Pattern.CASE_INSENSITIVE
        );
    
    private static final Pattern EXECUTION_PATTERN =
        Pattern.compile(
            "(?i)(" +
            // Original: execute/run/simplify it
            "^\\s*(run|execute|simply|simplify)\\s+.*(it|the code|result|program)|" +
            // NEW: "can you execute it", "just run it", "run it now"
            "^\\s*(can\\s+you\\s+)?(execute|run)\\s+it|" +
            "^\\s*just\\s+(run|execute)\\s+it|" +
            // NEW: "try same in java/python/go/node/bash"
            "try\\s+same\\s+in\\s+(java|python|go|node|bash|javascript|typescript|kotlin|ruby)|" +
            // NEW: "execute it and share result" (no 'simplify' prefix needed)
            "^\\s*(execute|run)\\s+it" +
            ")",
            Pattern.CASE_INSENSITIVE
        );

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(CODER_FORCE_PATTERN, EXECUTION_PATTERN);
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
