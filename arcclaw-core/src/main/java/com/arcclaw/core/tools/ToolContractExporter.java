package com.arcclaw.core.tools;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import com.arcclaw.core.agent.role.ToolMetadata;
import com.arcclaw.core.util.LogUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Exports all registered tool contracts to a versioned JSON file at startup.
 * <p>
 * Output location: {@code ${arcclaw.data-dir}/tool-contracts.json}
 * Contains each tool's name, description, schema, governance metadata,
 * required secrets, and role grants.
 */
@ApplicationScoped
public class ToolContractExporter {

    private static final Logger log = Logger.getLogger(ToolContractExporter.class);

    @Inject ToolRegistry toolRegistry;
    @Inject ToolDiscoveryService discoveryService;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url", defaultValue = "jdbc:sqlite:./data/arcclaw-meta.db")
    String jdbcUrl;

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

        List<Map<String, Object>> tools = toolRegistry.listTools().stream()
                .map(this::buildToolContract)
                .toList();
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
        String path = jdbcUrl.replace("jdbc:sqlite:", "");
        int qIndex = path.indexOf('?');
        if (qIndex > 0) path = path.substring(0, qIndex);
        Path parent = Path.of(path).getParent();
        return parent != null ? parent : Path.of(".");
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
