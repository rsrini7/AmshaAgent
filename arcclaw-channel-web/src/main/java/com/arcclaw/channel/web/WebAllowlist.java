package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelAllowlist;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WebAllowlist implements ChannelAllowlist {

    @ConfigProperty(name = "arcclaw.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @Inject
    Logger log;

    @Override
    public boolean isAllowed(String platformUserId, String username) {
        if (!requireAuth) {
            return platformUserId != null && !platformUserId.isBlank();
        }
        // TODO Phase 79: validate JWT bearer token from WebSocket upgrade header
        // For now, require-auth=true is reserved for future JWT integration
        log.warnf("require-auth=true but JWT validation not yet implemented — blocking user %s", platformUserId);
        return false;
    }

    @Override
    public boolean isSilentBlock() {
        return false;
    }

    @Override
    public String getBlockResponse() {
        return "Access denied.";
    }
}
