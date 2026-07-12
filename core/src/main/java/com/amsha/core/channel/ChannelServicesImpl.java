package com.amsha.core.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelServices;
import com.amsha.core.lifecycle.SessionHeartbeatTracker;
import com.amsha.core.lifecycle.WorkspaceManager;
import com.amsha.core.pipeline.SessionIdResolver;
import com.amsha.core.pipeline.TurnDispatchService;
import com.amsha.core.pipeline.TurnRequest;
import com.amsha.core.security.SessionOwnerResolver;

/**
 * Implementation of ChannelServices that bridges to Amsha Agent Core internals.
 */
@ApplicationScoped
public class ChannelServicesImpl implements ChannelServices {

    @Inject
    TurnDispatchService turnDispatchService;

    @Inject
    WorkspaceManager workspaceManager;

    @Inject
    SessionIdResolver sessionIdResolver;

    @Inject
    SessionHeartbeatTracker heartbeatTracker;

    @Inject
    Instance<SessionOwnerResolver> ownerResolver;

    @Override
    public String resolveOrCreateSession(String channelType, String channelId, String userId) {
        return sessionIdResolver.resolveOrCreate(channelType, channelId, userId);
    }

    @Override
    public CompletableFuture<Void> processAsync(ChannelMessage message) {
        return turnDispatchService
                .dispatchAsync(TurnRequest.mapped(message, message.correlationId()))
                .thenApply(_ -> null);
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

    @Override
    public boolean isValidIdentity(String credential) {
        return ownerResolver.isResolvable() && ownerResolver.get().isValid(credential);
    }
}
