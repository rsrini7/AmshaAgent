package com.arcclaw.app.channel;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelType;
import org.jboss.logging.Logger;

/**
 * Startup-validated registry of all channel plugins.
 * Fails fast at boot on duplicate channel type registrations.
 */
@ApplicationScoped
public class ChannelPluginRegistry {

    private static final Logger log = Logger.getLogger(ChannelPluginRegistry.class);

    @Inject @Any Instance<ChannelPlugin> plugins;

    private final Map<String, ChannelPlugin> registry = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        for (ChannelPlugin plugin : plugins) {
            String key = plugin.channelType().id();
            ChannelPlugin previous = registry.putIfAbsent(key, plugin);
            if (previous != null) {
                throw new IllegalStateException(
                    "Duplicate ChannelPlugin for type [" + key + "]: " +
                    previous.getClass().getName() + " vs " + plugin.getClass().getName());
            }
            log.infof("ChannelPluginRegistry: registered channel [%s] via %s",
                key, plugin.getClass().getSimpleName());
        }
        log.infof("ChannelPluginRegistry: %d channel(s) registered: %s",
            registry.size(), String.join(", ", registry.keySet()));
    }

    public Optional<ChannelPlugin> find(ChannelType type) {
        return Optional.ofNullable(registry.get(type.id()));
    }

    public Optional<ChannelPlugin> find(String channelTypeId) {
        return Optional.ofNullable(registry.get(channelTypeId));
    }
}
