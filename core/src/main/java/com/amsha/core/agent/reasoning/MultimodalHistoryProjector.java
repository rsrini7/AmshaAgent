package com.amsha.core.agent.reasoning;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import com.amsha.core.routing.ModelRoute;
import com.amsha.llm.api.MultimodalCapability;
import com.amsha.llm.api.MultimodalRequests;

/**
 * Projects historical conversation turns into representations compatible with the active
 * {@link ModelRoute} for the current turn.
 *
 * <p>Canonical conversation history in durable persistence retains full multimodal attachments.
 * When assembling prompt messages for a target model:
 * <ul>
 *   <li>Historical media content is preserved in full fidelity when the target route supports its modality and MIME type.</li>
 *   <li>Unsupported historical {@code ImageContent}, {@code AudioContent}, and {@code PdfFileContent} are safely converted into bounded descriptive text placeholders.</li>
 *   <li>Raw incompatible binary payloads are never forwarded to the target model.</li>
 * </ul>
 * </p>
 */
@ApplicationScoped
public class MultimodalHistoryProjector {

    /**
     * Projects a list of historical {@link ChatMessage} instances for the target {@link ModelRoute}.
     *
     * @param history the raw historical messages, never {@code null}
     * @param targetRoute the resolved route for the current turn, or {@code null}
     * @return projected messages safe for consumption by the target route
     */
    public List<ChatMessage> projectHistory(List<ChatMessage> history, ModelRoute targetRoute) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        if (targetRoute == null) {
            return projectHistory(history, MultimodalCapability.TEXT_ONLY, "unknown");
        }
        return projectHistory(history, targetRoute.capability(), targetRoute.modelName());
    }

    /**
     * Projects a list of historical {@link ChatMessage} instances given a target capability and model name.
     */
    public List<ChatMessage> projectHistory(
            List<ChatMessage> history, MultimodalCapability targetCapability, String targetModelName) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        MultimodalCapability cap = targetCapability != null ? targetCapability : MultimodalCapability.TEXT_ONLY;
        String modelName = targetModelName != null && !targetModelName.isBlank() ? targetModelName : "candidate model";

        ChatRequest tempRequest = ChatRequest.builder().messages(history).build();
        ChatRequest sanitized = MultimodalRequests.forCandidate(tempRequest, cap, modelName);
        return sanitized.messages();
    }

    /**
     * Projects a single {@link ChatMessage} for the target capability.
     */
    public ChatMessage projectMessage(
            ChatMessage message, MultimodalCapability targetCapability, String targetModelName) {
        if (message == null) {
            return null;
        }
        List<ChatMessage> projected = projectHistory(List.of(message), targetCapability, targetModelName);
        return projected.isEmpty() ? null : projected.getFirst();
    }
}
