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
