package com.amsha.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.amsha.core.agent.reasoning.ReasoningStep;
import com.amsha.core.agent.result.AgentResult;

import org.junit.jupiter.api.Test;

class AgentResultFormatterRecoveryTest {

    @Test
    void finishedMalformedToolArrayAnswerRecoversFromLatestSuccessfulToolObservation() {
        String observation = """
                [
                  {
                    "created_at": "2026-05-18T04:59:30Z",
                    "description": "mac mini installed",
                    "files": ["llama.cpp-mtp-ngram-mac.md", "zshrc"],
                    "id": "8b38283478fa48cc291f683f843dc50e"
                  },
                  {
                    "created_at": "2026-05-02T04:26:05Z",
                    "description": "Boris Cherny Rules",
                    "files": ["claude-code-rules.md"],
                    "id": "c3a19aa8baac835c8d6fc17cda5d89ea"
                  }
                ]
                """;
        ReasoningStep githubStep = ReasoningStep.of(
                "list gists",
                "github",
                Map.of("command", "list", "target", "rsrini7", "action", "gist"),
                observation,
                0,
                0,
                true);
        AgentResult finished = AgentResult.finished("""
                [{"name":"github","arguments":{"command":"list","target":"rsrini7","action":"gist"}}]
                """, List.of(githubStep));

        String formatted = AgentResultFormatter.format(finished);

        assertFalse(formatted.contains("Tool output was malformed"));
        assertTrue(formatted.contains("mac mini installed"));
        assertTrue(formatted.contains("llama.cpp-mtp-ngram-mac.md"));
        assertTrue(formatted.contains("Boris Cherny Rules"));
    }

    @Test
    void recoveredObservationMaxCharsIsConfigurableViaSystemProperty() {
        String key = "amsha.chat.recovered-observation-max-chars";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "12");

            String observation = "abcdefghijklmnopqrstuvwxyz";
            ReasoningStep step =
                    ReasoningStep.of("plain text", "github", Map.of("command", "list"), observation, 0, 0, true);
            AgentResult finished = AgentResult.finished("[{\"name\":\"github\",\"arguments\":{}}]", List.of(step));

            String formatted = AgentResultFormatter.format(finished);
            assertEquals("abcdefghijkl...", formatted);
        } finally {
            if (previous != null) {
                System.setProperty(key, previous);
            } else {
                System.clearProperty(key);
            }
        }
    }
}
