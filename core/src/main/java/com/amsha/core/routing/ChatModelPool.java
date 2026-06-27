package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

/**
 * Pool of ChatModel instances keyed by "${provider}#${model}".
 * Multiple personas sharing the same provider+model share a single instance.
 * Thread-safe via ConcurrentHashMap.
 */
@ApplicationScoped
public class ChatModelPool {

    private static final Logger log = Logger.getLogger(ChatModelPool.class);

    private final ConcurrentHashMap<String, ChatModel> pool = new ConcurrentHashMap<>();

    /**
     * Returns an existing model instance or creates one via the factory.
     *
     * @param poolKey  "${provider}#${model}" — the deduplication key
     * @param factory  Factory function to create the model if not pooled
     * @return The pooled (or newly created) ChatModel
     */
    public ChatModel getOrCreate(String poolKey, Supplier<ChatModel> factory) {
        return pool.computeIfAbsent(poolKey, k -> {
            log.infof("[MODEL-POOL] Creating new model instance: %s", k);
            return factory.get();
        });
    }

    /**
     * Returns the number of pooled model instances.
     */
    public int size() {
        return pool.size();
    }

    /**
     * Checks if a model is already pooled.
     */
    public boolean contains(String poolKey) {
        return pool.containsKey(poolKey);
    }

    /**
     * Builds a pool key from provider and model name.
     */
    public static String poolKey(String provider, String model) {
        return provider + "#" + model;
    }
}
