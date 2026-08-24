package com.amsha.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;

import java.util.Locale;

import com.amsha.llm.api.StructuredOutputMode;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;
import io.smallrye.common.annotation.Identifier;

/**
 * Produces the {@code compaction-mode} {@link StructuredOutputMode} bean.
 * <p>
 * The {@code compaction} {@link dev.langchain4j.model.chat.ChatModel} bean is now produced by
 * {@link InfiniteLoopCompactionChatModelProvider} with slot rotation.
 */
@ApplicationScoped
@Default
@Unremovable
public class CompactionModelProducer {

    private static final Logger log = Logger.getLogger(CompactionModelProducer.class);

    @ConfigProperty(name = "amsha.compaction.structured-output-mode", defaultValue = "PROMPT_DIRECTIVE")
    String compactionMode;

    @Produces
    @Dependent
    @Identifier("compaction-mode")
    public StructuredOutputMode compactionMode() {
        try {
            StructuredOutputMode mode = StructuredOutputMode.valueOf(compactionMode.toUpperCase(Locale.ROOT));
            log.infof("CompactionModelProducer structured-output-mode=%s", mode);
            return mode;
        } catch (IllegalArgumentException _) {
            return StructuredOutputMode.PROMPT_DIRECTIVE;
        }
    }
}
