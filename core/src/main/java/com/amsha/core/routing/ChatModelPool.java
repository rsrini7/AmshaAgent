package com.amsha.core.routing;

import dev.langchain4j.model.chat.ChatModel;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

/**
 * Pool of ChatModel instances keyed by "${provider}#${model}".
 * Multiple personas sharing the same provider+model share a single instance.
 * Thread-safe via ConcurrentHashMap.
 *
 * <p>For profile-scoped routing (O3), the pool also supports a typed key
 * {@code (scopeType, scopeId, connectionId, connectionRevision, profileId, profileRevision,
 * providerId, modelId)} so two accounts for the same model do not share a client, and maintains a
 * reverse index by connection/profile id for targeted invalidation. The pool may retain an
 * opaque credential-bearing provider client (the client authenticates future calls) but must not
 * separately cache decrypted byte arrays, VaultKey objects, raw secret DTOs, or loggable
 * credential material.</p>
 */
@ApplicationScoped
public class ChatModelPool {

    private static final Logger log = Logger.getLogger(ChatModelPool.class);

    private final ConcurrentHashMap<String, ChatModel> pool = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> connectionIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> profileIndex = new ConcurrentHashMap<>();
    private final Set<String> profileRouteKeys = ConcurrentHashMap.newKeySet();

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
     * Pools a route-level failover wrapper. These wrappers are invalidated as a group whenever
     * persisted profile topology changes because their composition is route-dependent.
     */
    public ChatModel getOrCreateProfileRoute(String routeKey, Supplier<ChatModel> factory) {
        profileRouteKeys.add(routeKey);
        return getOrCreate(routeKey, factory);
    }

    /**
     * Returns an existing profile-scoped model instance or creates one via the factory, registering
     * it in the connection/profile reverse index for targeted invalidation.
     */
    public ChatModel getOrCreateTyped(
            String scopeType,
            String scopeId,
            String connectionId,
            int connectionRevision,
            String profileId,
            int profileRevision,
            String providerId,
            String modelId,
            Supplier<ChatModel> factory) {
        String typedKey = typedKey(
                scopeType, scopeId, connectionId, connectionRevision, profileId, profileRevision, providerId, modelId);
        ChatModel model = pool.computeIfAbsent(typedKey, k -> {
            log.infof("[MODEL-POOL] Creating profile-scoped model instance: %s", k);
            return factory.get();
        });
        connectionIndex
                .computeIfAbsent(connectionId, c -> ConcurrentHashMap.newKeySet())
                .add(typedKey);
        profileIndex
                .computeIfAbsent(profileId, p -> ConcurrentHashMap.newKeySet())
                .add(typedKey);
        return model;
    }

    /**
     * Invalidates every pooled entry derived from a connection (e.g. after delete/rotation).
     */
    public void invalidateByConnection(String connectionId) {
        Set<String> keys = connectionIndex.remove(connectionId);
        if (keys != null) {
            keys.forEach(pool::remove);
            log.infof("[MODEL-POOL] Invalidated %d entries for connection %s", keys.size(), connectionId);
        }
    }

    /**
     * Invalidates every pooled entry derived from a profile.
     */
    public void invalidateByProfile(String profileId) {
        Set<String> keys = profileIndex.remove(profileId);
        if (keys != null) {
            keys.forEach(pool::remove);
            log.infof("[MODEL-POOL] Invalidated %d entries for profile %s", keys.size(), profileId);
        }
    }

    /** Invalidates all route-level profile wrappers after a route/profile topology mutation. */
    public void invalidateProfileRoutes() {
        profileRouteKeys.forEach(pool::remove);
        int invalidated = profileRouteKeys.size();
        profileRouteKeys.clear();
        if (invalidated > 0) {
            log.infof("[MODEL-POOL] Invalidated %d profile route wrappers", invalidated);
        }
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

    /**
     * Builds the typed profile-scoped pool key.
     */
    public static String typedKey(
            String scopeType,
            String scopeId,
            String connectionId,
            int connectionRevision,
            String profileId,
            int profileRevision,
            String providerId,
            String modelId) {
        return String.join(
                "#",
                scopeType,
                scopeId,
                connectionId,
                Integer.toString(connectionRevision),
                profileId,
                Integer.toString(profileRevision),
                providerId,
                modelId);
    }
}
