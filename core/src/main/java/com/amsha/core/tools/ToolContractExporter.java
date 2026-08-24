package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.amsha.common.util.LogUtil;
import com.amsha.tool.api.ToolMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;

/**
 * Exports all registered tool contracts to a versioned JSON file at startup.
 * <p>
 * Output location: {@code ${amsha.data-dir}/tool-contracts.json}
 * Contains each tool's name, description, schema, governance metadata,
 * required secrets, and role grants.
 */
@ApplicationScoped
public class ToolContractExporter {

    private static final Logger log = Logger.getLogger(ToolContractExporter.class);

    @Inject
    ToolRegistry toolRegistry;

    @Inject
    ToolDiscoveryService discoveryService;

    @Inject
    ToolCategoryMapper categoryMapper;

    @ConfigProperty(name = "amsha.data-dir", defaultValue = "./data")
    String dataDir;

    void onStart(@Observes StartupEvent ev) {
        try {
            exportContracts();
        } catch (Exception e) {
            log.errorf("ToolContractExporter: failed to write tool contracts:\n%s", LogUtil.trimmedStack(e));
        }
    }

    private void exportContracts() throws IOException {
        Path dir = dataDir();
        Files.createDirectories(dir);
        Path out = dir.resolve("tool-contracts.json");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("toolCount", toolRegistry.listTools().size());

        List<Map<String, Object>> tools =
                toolRegistry.listTools().stream().map(this::buildToolContract).toList();
        root.put("tools", tools);

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(out.toFile(), root);
        log.infof("ToolContractExporter: wrote %d tool contracts to %s", tools.size(), out);
    }

    private Map<String, Object> buildToolContract(String toolName) {
        var tool = toolRegistry.findTool(toolName).orElseThrow();
        var descriptor = discoveryService.getDescriptorCache().get(toolName);
        var secrets = discoveryService.getSecretsCache().getOrDefault(toolName, Map.of());

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("name", tool.name());
        contract.put("description", tool.description());
        contract.put("category", categoryMapper.categoryFor(tool.name()).name());

        // @ToolMetadata annotation
        ToolMetadata meta = findAnnotation(tool.getClass(), ToolMetadata.class);
        if (meta != null) {
            contract.put("capabilityId", meta.capabilityId());
            contract.put("defaultRoles", List.of(meta.defaultRoles()));
        }

        // Governance
        contract.put("requiredOperation", tool.requiredOperation().name());
        contract.put("riskLevel", tool.requiredOperation().riskLevel().name());
        contract.put("requiresSandbox", tool.requiresSandbox());
        contract.put("idempotencyMode", tool.idempotencyMode().name());

        // Schema
        if (descriptor != null && descriptor.inputSchema() != null) {
            contract.put("inputSchema", descriptor.inputSchema());
        }

        // Secrets
        if (!secrets.isEmpty()) {
            contract.put("requiredSecrets", secrets);
        }

        return contract;
    }

    private Path dataDir() {
        if (LaunchMode.current() == LaunchMode.TEST && "./data".equals(dataDir)) {
            return Path.of("target");
        }
        return Path.of(dataDir);
    }

    private static <T extends Annotation> T findAnnotation(Class<?> clazz, Class<T> ann) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            T a = c.getAnnotation(ann);
            if (a != null) return a;
            c = c.getSuperclass();
        }
        return null;
    }
}
