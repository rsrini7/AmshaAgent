package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelAllowlist;

import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class WebAllowlist implements ChannelAllowlist {

    @ConfigProperty(name = "arcclaw.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @Override
    public boolean isAllowed(String platformUserId, String username) {
        // Phase 79: If requireAuth is true, the WebSocket edge (WebChatEndpoint)
        // already validated the API key upon connection setup. All messages reaching
        // the pipeline are inherently from an authenticated session.
        return platformUserId != null && !platformUserId.isBlank();
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
