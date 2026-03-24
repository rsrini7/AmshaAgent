package com.arcclaw.app.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import jakarta.inject.Inject;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

public class CompactionModelProducerTest {

    public static class DefaultProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.langchain4j.openai.api-key", "default-openai-key",
                "quarkus.langchain4j.openai.base-url", "http://default-openai-url",
                "arcclaw.llm.provider", "openai"
                // Do not set arcclaw.memory.compaction.api-key directly to test fallback
            );
        }
    }

    public static class OverrideProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.langchain4j.openai.api-key", "default-openai-key",
                "arcclaw.memory.compaction.api-key", "specific-compaction-key",
                "arcclaw.llm.provider", "openai"
            );
        }
    }

    @QuarkusTest
    @TestProfile(DefaultProfile.class)
    public static class DefaultFallbackTest {
        
        @Inject
        CompactionModelProducer producer;

        @ConfigProperty(name = "arcclaw.memory.compaction.api-key")
        String resolvedApiKey;

        @Test
        void testFallbackToOpenAiKey() {
            assertEquals("default-openai-key", resolvedApiKey, 
                "Should fall back to quarkus.langchain4j.openai.api-key when specific key is not set");
            
            ChatModel model = producer.compactionModel();
            assertNotNull(model, "Produced model should be non-null");
        }
    }

    @QuarkusTest
    @TestProfile(OverrideProfile.class)
    public static class OverrideKeyTest {

        @Inject
        CompactionModelProducer producer;

        @ConfigProperty(name = "arcclaw.memory.compaction.api-key")
        String resolvedApiKey;

        @Test
        void testSpecificKeyOverride() {
            assertEquals("specific-compaction-key", resolvedApiKey, 
                "Should use specific compaction key when set");
            
            ChatModel model = producer.compactionModel();
            assertNotNull(model, "Produced model should be non-null");
        }
    }
}
