package com.arcclaw.core.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Produces;

import com.arcclaw.core.agent.reasoning.StructuredOutputMode;

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

    @Produces
    @Dependent
    @Identifier("compaction-mode")
    public StructuredOutputMode compactionMode() {
        StructuredOutputMode mode = StructuredOutputMode.NATIVE;
        log.infof("CompactionModelProducer structured-output-mode=%s", mode);
        return mode;
    }
}
