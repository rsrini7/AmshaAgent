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

    @ConfigProperty(name = "arcclaw.api.keys.internal", defaultValue = "key-internal-changeme")
    String internalKey;

    @ConfigProperty(name = "arcclaw.api.keys.ci-pipeline", defaultValue = "key-ci-changeme")
    String ciKey;

    @Override
    public boolean isAllowed(String platformUserId, String username) {
        if (!requireAuth) {
            return platformUserId != null && !platformUserId.isBlank();
        }
        
        // Phase 79: Validate raw Developer API Keys instead of JWT
        if (internalKey.equals(platformUserId) || ciKey.equals(platformUserId)) {
            return true;
        }

        log.warnf("require-auth=true but provided API Key (platformUserId) is invalid. Blocking user.");
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
