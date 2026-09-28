package com.amsha.core.classifier.remote;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import org.junit.jupiter.api.Test;

/**
 * Guards the credential-handling contract of {@link RemoteProviderConfig}: the API key must be usable
 * in-process but must never reach a serializer.
 *
 * <p>{@code toString()} was already redacted, but a record's generated Jackson view is a separate
 * surface — telemetry, audit, or a REST response would have emitted the raw bearer token.
 */
class RemoteProviderConfigSerializationTest {

    private static final String SECRET = "super-secret-bearer-token";

    private static RemoteProviderConfig configWithApiKey() {
        // Only the builder defaults are relied on, so endpoint validation cannot make this test flaky.
        return RemoteProviderConfig.builder()
                .enabled(true)
                .apiKey(SECRET)
                .tenantIsolation(true)
                .build();
    }

    /** Resolves the Jackson serialization view of one property, or {@code null} if it is unknown. */
    private static BeanPropertyDefinition serializationProperty(String name) {
        ObjectMapper mapper = new ObjectMapper();
        BeanDescription description =
                mapper.getSerializationConfig().introspect(mapper.constructType(RemoteProviderConfig.class));
        return description.findProperties().stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void apiKeyIsNotSerializable() {
        BeanPropertyDefinition apiKey = serializationProperty("apiKey");

        assertNotNull(apiKey, "apiKey must stay a known property so it can still be bound from configuration");
        assertFalse(apiKey.couldSerialize(), "apiKey must never be serialized: it is a bearer credential");
    }

    @Test
    void controlPropertyRemainsSerializable() {
        // Proves the assertion above is not vacuous: if introspection reported every property as
        // non-serializable, apiKeyIsNotSerializable() would pass without guarding anything.
        BeanPropertyDefinition endpoint = serializationProperty("endpoint");

        assertNotNull(endpoint);
        assertTrue(endpoint.couldSerialize(), "endpoint must remain serializable");
    }

    @Test
    void toStringDoesNotLeakApiKey() {
        String rendered = configWithApiKey().toString();

        assertFalse(rendered.contains(SECRET), rendered);
    }

    @Test
    void apiKeyRemainsReadableInProcess() {
        assertTrue(configWithApiKey().optionalApiKey().isPresent());
    }
}
