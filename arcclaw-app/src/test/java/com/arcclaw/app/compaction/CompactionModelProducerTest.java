package com.arcclaw.app.compaction;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.inject.Inject;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.common.annotation.Identifier;

public class CompactionModelProducerTest {

    public static class DefaultProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "arcclaw.infinite-loop.compaction.providers", "groq",
                    "arcclaw.compaction.groq.api-key", "test-groq-key",
                    "arcclaw.compaction.groq.model", "llama-3.1-8b-instant",
                    "arcclaw.compaction.groq.base-url", "https://api.groq.com/openai/v1");
        }
    }

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "arcclaw.infinite-loop.compaction.providers", "groq",
                    "arcclaw.compaction.groq.api-key", "specific-compaction-key",
                    "arcclaw.compaction.groq.model", "llama-3.1-8b-instant",
                    "arcclaw.compaction.groq.base-url", "https://api.groq.com/openai/v1");
        }
    }

    @QuarkusTest
    @TestProfile(DefaultProfile.class)
    public static class DefaultFallbackTest {

        @Inject
        @Identifier("compaction")
        ChatModel compactionModel;

        @Test
        void testCompactionModelCreated() {
            assertNotNull(compactionModel, "Produced compaction model should be non-null");
        }
    }

    @QuarkusTest
    @TestProfile(OverrideProfile.class)
    public static class OverrideKeyTest {

        @Inject
        @Identifier("compaction")
        ChatModel compactionModel;

        @Test
        void testCompactionModelWithOverrideKey() {
            assertNotNull(compactionModel, "Produced compaction model should be non-null");
        }
    }
}
