package com.arcclaw.core.routing;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;
import dev.langchain4j.model.chat.ChatModel;
import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Produces the {@code compaction} {@link ChatModel} bean.
 * Reads provider config and delegates to {@link LlmModelFactory}.
 */
@ApplicationScoped
@Default
@Unremovable
public class CompactionModelProducer {

    private static final Logger log = Logger.getLogger(CompactionModelProducer.class);

    @ConfigProperty(name = "arcclaw.memory.compaction.provider", defaultValue = "groq")
    String provider;

    @ConfigProperty(name = "arcclaw.memory.compaction.base-url",
                    defaultValue = "https://api.groq.com/openai/v1")
    String compactionBaseUrl;

    @ConfigProperty(name = "arcclaw.memory.compaction.api-key",
                    defaultValue = "not-configured")
    String compactionApiKey;

    @ConfigProperty(name = "arcclaw.memory.compaction.model", defaultValue = "llama-3.1-8b-instant")
    String modelId;

    @ConfigProperty(name = "arcclaw.memory.compaction.max-output-tokens", defaultValue = "1024")
    int compactionMaxTokens;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.base-url", defaultValue = "http://localhost:11434")
    String ollamaBaseUrl;

    @ConfigProperty(name = "arcclaw.memory.compaction.ollama-model", defaultValue = "llama3.2")
    String ollamaModelId;

    @ConfigProperty(name = "arcclaw.memory.compaction.max-retries", defaultValue = "3")
    int maxRetries;

    @ConfigProperty(name = "arcclaw.memory.compaction.structured-output-mode", defaultValue = "NATIVE")
    StructuredOutputMode structuredOutputMode;

    @Inject
    LlmModelFactory modelFactory;

    @Produces
    @Identifier("compaction")
    @ApplicationScoped
    public ChatModel compactionModel() {
        String cleanProvider = clean(provider);
        log.infof("CompactionModelProducer compaction provider=%s model=%s mode=%s", 
                  cleanProvider, modelId, structuredOutputMode);
        return modelFactory.createModel(
            cleanProvider, compactionBaseUrl, compactionApiKey,
            modelId, compactionMaxTokens, Duration.ofSeconds(10),
            maxRetries, ollamaBaseUrl, ollamaModelId, structuredOutputMode, true);
    }

    private String clean(String v) {
        if (v == null) return null;
        return v.split("#")[0].trim();
    }

    @Produces
    @Identifier("compaction-mode")
    @Dependent
    @Unremovable
    public StructuredOutputMode compactionMode() {
        return structuredOutputMode;
    }
}
