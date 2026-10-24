package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.amsha.core.agent.role.AgentRoleEnum;

@ApplicationScoped
public class CoderRoutingProvider implements IntentRoutingProvider {
    private static final Pattern CODER_FORCE_PATTERN = Pattern.compile(
            "(?i)^\\s*(write|create|generate|implement|code|build|make|execute|run|share)\\s+"
                    + ".*(program|function|method|class|algorithm|code|script|snippet|result|"
                    + "fibonacci|fib|sort|search|binary|linked.?list|tree|graph|"
                    + "hello.?world|factorial|prime|palindrome|anagram)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXECUTION_PATTERN = Pattern.compile(
            "(?i)(" +
                    // Original: execute/run/simplify it
                    "^\\s*(run|execute|simply|simplify)\\s+.*(it|the code|result|program)|"
                    +
                    // NEW: "can you execute it", "just run it", "run it now"
                    "^\\s*(can\\s+you\\s+)?(execute|run)\\s+it|"
                    + "^\\s*just\\s+(run|execute)\\s+it|"
                    +
                    // NEW: "try same in java/python/go/node/bash"
                    "(?i)try\\s+same\\s+in\\s+(java|python|go|node|bash|javascript|typescript|kotlin|ruby)"
                    + ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_EXECUTION_PATTERN = Pattern.compile(
            "(?=.*\\b(?:run|execute|exec|compile|sandbox)\\b)"
                    + "(?=.*\\b(?:code|program|script|snippet|function|class|method|java|python|python3|node|javascript|typescript|js|ts|go|golang|rust|bash|shell|sql|kotlin|html|css|json|yaml|xml)\\b).*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXECUTION_NEGATION_PATTERN = Pattern.compile(
            "(?:\\b(?:do\\s+not|don'?t|never|avoid|not)\\s+"
                    + "(?:(?:ever|just|actually|really)\\s+)*"
                    + "(?:run|running|execute|executing|exec|compile|compiling|sandbox)\\b"
                    + "|\\b(?:do\\s+not|don'?t)\\s+(?:want|need|intend|plan|mean)\\b"
                    + "(?:\\s+[\\p{Alnum}_'-]+){0,4}\\s+to\\s+"
                    + "(?:run|execute|exec|compile|sandbox)\\b"
                    + "|\\bwithout\\s+(?:running|executing|compiling|using\\s+sandbox)\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INFORMATIONAL_QUESTION_PATTERN =
            Pattern.compile("^\\s*(?:how|what|why|which|where|when)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public List<Pattern> fastPathTriggers() {
        return List.of(CODER_FORCE_PATTERN, EXECUTION_PATTERN, CODE_EXECUTION_PATTERN);
    }

    @Override
    public boolean shouldSkip(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        return EXECUTION_NEGATION_PATTERN.matcher(userMessage).find()
                || INFORMATIONAL_QUESTION_PATTERN.matcher(userMessage).find();
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
                "If the user is asking to write, build, or implement NEW software logic or code, route to 'brahma/coder'.");
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
