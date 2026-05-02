package com.arcclaw.core.agent.reasoning;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.reasoning.error.LlmProviderException;
import com.arcclaw.core.agent.reasoning.error.LlmRateLimitException;
import com.arcclaw.core.agent.reasoning.port.LlmProviderPort;
import com.arcclaw.core.governance.BudgetExceededException;
import com.arcclaw.core.governance.BudgetWarningException;
import com.arcclaw.core.governance.GovernanceStrategy;
import com.arcclaw.core.observability.AgentOtelContext;
import com.arcclaw.core.util.AgentErrorMessageUtil;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import org.jboss.logging.Logger;

/**
 * Handles structured output generation by interacting with the LLM provider.
 */
@ApplicationScoped
public class StructuredOutputProcessor {

    private static final Logger log = Logger.getLogger(StructuredOutputProcessor.class);

    private final SystemPromptBuilder promptBuilder;
    private final LlmProviderPort llmProviderPort;
    private final GovernanceStrategy governanceLayer;
    private final AgentOtelContext otelContext;

    @Inject
    public StructuredOutputProcessor(SystemPromptBuilder promptBuilder,
                                    LlmProviderPort llmProviderPort,
                                    GovernanceStrategy governanceLayer,
                                    AgentOtelContext otelContext) {
        this.promptBuilder = promptBuilder;
        this.llmProviderPort = llmProviderPort;
        this.governanceLayer = governanceLayer;
        this.otelContext = otelContext;
    }

    protected StructuredOutputProcessor() {
        this(null, null, null, null);
    }

    public String chatForStructuredOutput(String sessionId, String agentRole, String schemaPrompt) {
        Span span = otelContext.childSpan(sessionId, "structured_output_generation");
        try {
            String systemPrompt = promptBuilder.build(null, agentRole, sessionId, "", false);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.systemMessage(systemPrompt));
            messages.add(UserMessage.userMessage(schemaPrompt));

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .build();

            ChatResponse response = llmProviderPort.nextStep(request);

            TokenUsage usage = response.tokenUsage();
            if (usage != null) {
                try {
                    governanceLayer.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                    otelContext.recordLlmCall(sessionId, usage.inputTokenCount(), usage.outputTokenCount());
                } catch (BudgetWarningException w) {
                    log.warnf("Budget warning for session %s structured call: %s", sessionId, AgentErrorMessageUtil.safeMessage(w));
                    throw w;
                } catch (BudgetExceededException be) {
                    log.warnf("Budget exceeded in structured output for session %s: %s", sessionId, AgentErrorMessageUtil.safeMessage(be));
                    throw be;
                }
            }

            return response.aiMessage().text();
        } catch (BudgetWarningException | BudgetExceededException e) {
            throw e;
        } catch (LlmProviderException e) {
            if (e instanceof LlmRateLimitException) {
                log.warnf("[StructuredOutputProcessor] Rate limit in structured output - %s", e.getMessage());
                String clean = AgentErrorMessageUtil.sanitizeErrorMessage(e.getMessage());
                throw new RuntimeException("Structured output LLM call failed: " + clean, e);
            }
            String clean = AgentErrorMessageUtil.sanitizeErrorMessage(AgentErrorMessageUtil.safeMessage(e));
            throw new RuntimeException("Structured output LLM call failed: " + clean, e);
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }
}
