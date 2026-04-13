package com.arcclaw.channel.web;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

@ApplicationScoped
public class WsTokenStore {
    private final Cache<String, Boolean> tokenCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    public String generateToken() {
        String token = UUID.randomUUID().toString();
        tokenCache.put(token, Boolean.TRUE);
        return token;
    }

    public boolean validateAndConsumeToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Boolean exists = tokenCache.getIfPresent(token);
        if (exists != null && exists) {
            tokenCache.invalidate(token);
            return true;
        }
        return false;
    }
}
