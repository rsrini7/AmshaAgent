package com.arcclaw.app.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

import com.arcclaw.core.security.SessionOwnerResolver;

/**
 * Bridges the REST API key registry into the core SessionOwnerResolver SPI.
 */
@ApplicationScoped
public class ApiKeyOwnerResolver implements SessionOwnerResolver {

    @Inject
    ApiKeyValidator validator;

    @Override
    public Optional<String> resolveOwner(String apiKey) {
        return Optional.ofNullable(validator.resolveUserId(apiKey));
    }

    @Override
    public boolean isValid(String apiKey) {
        return validator.isValid(apiKey);
    }
}
