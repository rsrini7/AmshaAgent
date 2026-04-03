package com.arcclaw.core.channel;

import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelMessage;
import com.arcclaw.channel.api.ChannelServices;
import com.arcclaw.core.lifecycle.WorkspaceManager;
import com.arcclaw.core.pipeline.MessageContext;
import com.arcclaw.core.pipeline.MessagePipeline;
import com.arcclaw.core.pipeline.SessionIdResolver;
import com.arcclaw.core.security.SessionOwnerResolver;
import com.arcclaw.core.lifecycle.SessionHeartbeatTracker;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Implementation of ChannelServices that bridges to ArcClaw Core internals.
 */
@ApplicationScoped
public class ChannelServicesImpl implements ChannelServices {

    @Inject MessagePipeline messagePipeline;
    @Inject WorkspaceManager workspaceManager;
    @Inject SessionIdResolver sessionIdResolver;
    @Inject SessionHeartbeatTracker heartbeatTracker;
    @Inject SessionOwnerResolver ownerResolver;
    @Inject ManagedExecutor executor;

    @Override
    public String resolveOrCreateSession(String channelType, String channelId, String userId) {
        return sessionIdResolver.resolveOrCreate(channelType, channelId, userId);
    }

    @Override
    public CompletableFuture<Void> processAsync(ChannelMessage message) {
        MessageContext context = new MessageContext(
            message.correlationId(), 
            message.sender(), 
            message);
            
        return executor.runAsync(() -> messagePipeline.process(context));
    }

    @Override
    public void terminateSession(String sessionId) {
        workspaceManager.terminateSession(sessionId);
    }

    @Override
    public void recordHeartbeat(String sessionId) {
        heartbeatTracker.recordHeartbeat(sessionId);
    }

    @Override
    public void bindSessionOwner(String sessionId, String ownerKeyName) {
        heartbeatTracker.tryBindOwner(sessionId, ownerKeyName);
    }
}
