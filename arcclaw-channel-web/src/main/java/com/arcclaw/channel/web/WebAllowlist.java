package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelAllowlist;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WebAllowlist implements ChannelAllowlist {

    @ConfigProperty(name = "arcclaw.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @jakarta.inject.Inject
    org.jboss.logging.Logger log;

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
