package com.amsha.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.amsha.core.security.AutonomyLevel;

import org.junit.jupiter.api.Test;

class ClassifierContextPrivacyTest {

    private static final String SECRET = "sentinel-secret-value";

    @Test
    void toolRiskContextSummariesExcludeGoalAndParameterPayloads() {
        Map<String, Object> parameters = Map.of("authorization", SECRET);
        PostPolicyToolContext active =
                new PostPolicyToolContext("coder", AutonomyLevel.FULL, "host_exec", parameters, SECRET, false);
        ToolRiskShadowContext shadow =
                new ToolRiskShadowContext("coder", AutonomyLevel.FULL, "host_exec", parameters, SECRET);

        assertPayloadFree(active.toString());
        assertPayloadFree(shadow.toString());
        assertTrue(active.toString().contains("parameterCount=1"));
        assertTrue(shadow.toString().contains("parameterCount=1"));
    }

    @Test
    void answerQualityContextSummariesExcludeGoalAndAnswerPayloads() {
        AnswerQualityContext active = new AnswerQualityContext("researcher", SECRET, SECRET, true);
        AnswerQualityShadowContext shadow = new AnswerQualityShadowContext("researcher", SECRET, SECRET, true);

        assertPayloadFree(active.toString());
        assertPayloadFree(shadow.toString());
        assertTrue(active.toString().contains("toolInvokedInTurn=true"));
        assertTrue(shadow.toString().contains("toolInvokedInTurn=true"));
    }

    private static void assertPayloadFree(String summary) {
        assertFalse(summary.contains(SECRET));
        assertFalse(summary.contains("authorization"));
    }
}
