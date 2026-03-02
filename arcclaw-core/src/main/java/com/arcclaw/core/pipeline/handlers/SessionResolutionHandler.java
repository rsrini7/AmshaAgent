package com.arcclaw.core.pipeline.handlers;

import com.arcclaw.core.pipeline.HandleResult;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessageHandler;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Resolves the session ID for the incoming message.
 */
@ApplicationScoped
public class SessionResolutionHandler implements MessageHandler {
    private static final Logger log = Logger.getLogger(SessionResolutionHandler.class);

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public HandleResult handle(MessageContext context) {
        // Chat ID is the primary key for session lookup in channel mode
        String chatId = context.getChatId();
        String userId = context.getUser().platformId();
        
        String sessionId = UUID.nameUUIDFromBytes((chatId + ":" + userId)
                .getBytes(StandardCharsets.UTF_8)).toString();
                
        context.setSessionId(sessionId);
        log.debugf("Resolved session %s for chat %s", sessionId, chatId);
        
        return HandleResult.CONTINUE;
    }
}
