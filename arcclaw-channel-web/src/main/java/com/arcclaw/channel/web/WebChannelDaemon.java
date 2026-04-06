package com.arcclaw.channel.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelDaemon;
import com.arcclaw.channel.api.ChannelType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WebChannelDaemon implements ChannelDaemon {
    private static final Logger log = Logger.getLogger(WebChannelDaemon.class);

    @ConfigProperty(name = "arcclaw.channel.web.enabled", defaultValue = "true")
    boolean enabled;

    @Inject WebSocketSessionStore sessionStore;

    @Override
    public String channelId() {
        return ChannelType.WEBSOCKET.id();
    }

    @Override
    public boolean isConfigured() {
        return enabled;
    }

    @Override
    public void start() {
        log.infof("Web channel daemon started — WebSocket endpoint /ws/chat active.");
    }

    // While ChannelDaemon doesn't strictly define stop() in its API, we can provide it
    // if there's a lifecycle observer, or just leave it out if the framework doesn't 
    // call it via the API. The prompt had a stop() method for logging.
    // For now we will add a method that can be called on bean destruction or similar if needed.
    public void stop() {
        int count = sessionStore.all().size();
        sessionStore.closeAll();
        log.infof("Web channel daemon stopped — closed %d WebSocket sessions.", count);
    }
}
