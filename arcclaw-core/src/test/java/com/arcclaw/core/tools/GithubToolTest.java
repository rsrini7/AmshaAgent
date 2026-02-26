package com.arcclaw.core.tools;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubToolTest {

    private final GithubTool tool = new GithubTool();

    @Test
    void testDeleteCommandBlocked() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "delete",
            "target", "rsrini7/arcclaw"
        );
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(params));
        assertTrue(exception.getMessage().contains("not permitted"));
    }

    @Test
    void testArchiveCommandBlocked() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "archive",
            "target", "rsrini7/arcclaw"
        );
        assertThrows(IllegalArgumentException.class, () -> tool.sandboxCommand(params));
    }

    @Test
    void testViewStillWorks() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "view",
            "target", "rsrini7/arcclaw"
        );
        assertDoesNotThrow(() -> tool.sandboxCommand(params));
    }

    @Test
    void testListStillWorks() {
        Map<String, Object> params = Map.of(
            "action", "repo",
            "command", "list",
            "target", "rsrini7"
        );
        assertDoesNotThrow(() -> tool.sandboxCommand(params));
    }
}
