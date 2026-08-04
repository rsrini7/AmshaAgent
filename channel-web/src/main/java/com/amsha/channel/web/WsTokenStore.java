package com.amsha.channel.web;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@ApplicationScoped
public class WsTokenStore {
    private final Cache<String, String> tokenCache =
            Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();

    public String generateToken(String ownerKeyName) {
        String token = UUID.randomUUID().toString();
        tokenCache.put(token, ownerKeyName);
        return token;
    }

    public Optional<String> validateAndConsumeToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokenCache.asMap().remove(token));
    }
}
