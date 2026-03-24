package com.arcclaw.core.routing;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DefaultRoutingProvider implements IntentRoutingProvider {
    @Override
    public List<Pattern> fastPathTriggers() {
        return Collections.emptyList();
    }

    @Override
    public List<String> promptDirectives() {
        return List.of(
            "(UNLESS SCHEDULING) If the user mentions GitHub, gists, or repositories to LIST, FETCH, or VIEW existing content -> researcher.",
            "(UNLESS SCHEDULING) If the user mentions GitHub, gists, or repositories to CREATE, BUILD, or GENERATE new content -> coder.",
            "Only output 'debugger' if the user provides actual error text, stack traces, or explicitly says \"fix\", \"broken\", \"not working\" with technical detail. Negative words like \"no\", \"none\", or \"missing\" alone are NOT debugging signals.",
            "\"Hi\", \"Hello\", greetings, or vague messages -> coordinator only.",
            "Phrases like \"then in X\", \"and then X\", \"also X\" are CONTINUATION phrases joining two code/writing requests — NEVER treat them as scheduling signals. Only route to coordinator for scheduling if the user explicitly uses time words like \"in 5 mins\", \"at 3pm\", \"every day\", \"after 2 hours\", \"remind me\", \"schedule\"."
        );
    }

    @Override
    public Optional<String> fastPathRole() {
        return Optional.empty();
    }

    @Override
    public int priority() {
        return 1000;
    }
}
