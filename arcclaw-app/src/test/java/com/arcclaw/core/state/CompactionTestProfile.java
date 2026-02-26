package com.arcclaw.core.state;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class CompactionTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "arcclaw.memory.compaction.enabled", "true",
                "arcclaw.memory.compaction.threshold", "4",
                "arcclaw.memory.compaction.keep-recent", "2"
        );
    }
}
