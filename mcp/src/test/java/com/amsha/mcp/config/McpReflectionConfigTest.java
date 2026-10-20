package com.amsha.mcp.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.amsha.mcp.bridge.McpToolDescriptor;
import com.amsha.mcp.bridge.McpToolRequest;
import com.amsha.mcp.tool.DynamicToolCapability;
import com.amsha.mcp.transport.HeaderMetadata;
import com.amsha.mcp.transport.HeaderSecretReference;

import org.junit.jupiter.api.Test;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Guards the native-image reflection registration (§6.7).
 *
 * <p>Every {@code classNames} entry is a string, so a plain rename breaks native builds silently — nothing at
 * compile time or in the JVM test suite notices. Resolving each name is the cheapest way to make that
 * impossible.
 */
class McpReflectionConfigTest {

    private static RegisterForReflection registration() {
        RegisterForReflection annotation = McpReflectionConfig.class.getAnnotation(RegisterForReflection.class);
        assertNotNull(annotation, "McpReflectionConfig must carry @RegisterForReflection");
        return annotation;
    }

    @Test
    void everyRegisteredClassNameResolvesToARealClass() {
        RegisterForReflection annotation = registration();

        assertTrue(annotation.classNames().length > 0, "the registration must not be empty");
        for (String className : annotation.classNames()) {
            assertDoesNotThrow(
                    () -> Class.forName(className),
                    () -> "a native-image reflection entry no longer resolves (renamed or deleted): " + className);
        }
    }

    @Test
    void theReflectionTargetsCoverTheMcpDtos() {
        List<Class<?>> targets = List.of(registration().targets());

        assertTrue(
                targets.containsAll(List.of(
                        McpToolDescriptor.class,
                        McpToolRequest.class,
                        DynamicToolCapability.class,
                        HeaderSecretReference.class,
                        HeaderMetadata.class)),
                () -> "MCP DTOs missing from the native reflection targets: " + targets);
    }

    @Test
    void theConfigIsInstantiable() {
        assertNotNull(new McpReflectionConfig());
    }
}
