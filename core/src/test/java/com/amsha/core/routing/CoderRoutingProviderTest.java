package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoderRoutingProviderTest {

    private final CoderRoutingProvider provider = new CoderRoutingProvider();

    @Test
    void routesCodeExecutionRequestsToCoder() {
        assertMatches("Konjam java code ezhuthi atha run panni kodunga fact 7");
        assertMatches("write a python script and execute it");
        assertMatches("Can you run this Java code?");
    }

    @Test
    void doesNotRouteNonCodeExecution() {
        assertNoMatch("run the report");
    }

    @Test
    void doesNotRouteNegatedExecution() {
        assertNoMatch("Do not execute this Python script");
        assertNoMatch("Don't ever run this Java program");
        assertNoMatch("Avoid running this code");
    }

    @Test
    void recognizesNegationWithInterveningWords() {
        assertNoMatch("I don't want you to run this Python script");
        assertNoMatch("I do not want to execute this Java code");
        assertNoMatch("Do not really run this Python program");
    }

    @Test
    void doesNotTreatNotOnlyAsNegation() {
        assertMatches("not only run this Java code");
    }

    @Test
    void doesNotRouteInformationalQuestions() {
        assertNoMatch("How do I run this Java code?");
        assertNoMatch("What does this Python script do?");
    }

    private void assertMatches(String input) {
        assertFalse(provider.shouldSkip(input), "should not skip: " + input);
        assertTrue(
                provider.fastPathTriggers().stream()
                        .anyMatch(p -> p.matcher(input).find()),
                "should match: " + input);
    }

    private void assertNoMatch(String input) {
        if (provider.shouldSkip(input)) {
            return;
        }
        assertFalse(
                provider.fastPathTriggers().stream()
                        .anyMatch(p -> p.matcher(input).find()),
                "should not match: " + input);
    }
}
