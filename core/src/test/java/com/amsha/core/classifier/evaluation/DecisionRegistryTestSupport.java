package com.amsha.core.classifier.evaluation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Helpers for proving the registries reject malformed classpath artifacts. */
final class DecisionRegistryTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DecisionRegistryTestSupport() {}

    static ObjectMapper lenientMapper() {
        return JSON.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    static byte[] resourceWithUnknownField(String resourcePath, String fieldName) throws IOException {
        try (InputStream resource =
                DecisionRegistryTestSupport.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (resource == null) {
                throw new IOException("Missing test resource " + resourcePath);
            }
            JsonNode tree = JSON.readTree(resource);
            if (!(tree instanceof ObjectNode root)) {
                throw new IOException("Expected an object at " + resourcePath);
            }
            root.put(fieldName, true);
            return JSON.writeValueAsBytes(root);
        }
    }

    /**
     * Runs {@code operation} with a context classloader that is present and functional but cannot see
     * {@code resourcePath}.
     *
     * <p>This is the shape a registry must survive in a native image or a container: the thread carries a
     * context loader that resolves other resources yet returns null for an artifact the application ships.
     * A lookup that consults the context loader and gives up on a miss reports that artifact as missing; a
     * lookup that then tries its own classloader still finds it. The delegating loader {@link #withResource}
     * installs cannot reproduce this, because it never misses.
     */
    static <T> T withResourceHidden(String resourcePath, Supplier<T> operation) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(operation, "operation");

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader hiding = new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (resourcePath.equals(name)) {
                    return null;
                }
                return super.getResourceAsStream(name);
            }
        };
        thread.setContextClassLoader(hiding);
        try {
            return operation.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    static <T> T withResource(String resourcePath, byte[] replacement, Supplier<T> operation) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        byte[] resourceBytes =
                Objects.requireNonNull(replacement, "replacement").clone();
        Objects.requireNonNull(operation, "operation");

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ClassLoader override = new ClassLoader(original) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (resourcePath.equals(name)) {
                    return new ByteArrayInputStream(resourceBytes);
                }
                return super.getResourceAsStream(name);
            }
        };
        thread.setContextClassLoader(override);
        try {
            return operation.get();
        } finally {
            thread.setContextClassLoader(original);
        }
    }
}
