package com.arcclaw.mcp.transport;

import com.arcclaw.core.sandbox.SandboxCircuitBreaker;
import com.arcclaw.mcp.tool.DynamicToolCapability;
import com.arcclaw.mcp.tool.DynamicToolCapabilityRegistry;
import com.arcclaw.mcp.tool.McpTransportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

@ApplicationScoped
public class PodmanMcpTransport {
    private static final Logger log = Logger.getLogger(PodmanMcpTransport.class);

    @Inject SandboxCircuitBreaker sandboxCircuitBreaker;
    @Inject DynamicToolCapabilityRegistry registry;
    @Inject ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OutputStream> writers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedReader> readers = new ConcurrentHashMap<>();
    private final AtomicInteger requestIdGenerator = new AtomicInteger(1);

    public Uni<Void> connect(McpServerConfig config) {
        return Uni.createFrom().item(() -> {
            if (sandboxCircuitBreaker.getState() == SandboxCircuitBreaker.CircuitBreakerState.OPEN) {
                if (!sandboxCircuitBreaker.shouldAttemptPodman()) {
                    throw new RuntimeException("MCP Podman circuit breaker is OPEN for server: " + config.serverName());
                }
            }

            try {
                List<String> command = new ArrayList<>();
                command.add("podman");
                command.add("run");
                command.add("--rm");
                command.add("-i");
                command.add("--network=none");
                
                for (String env : config.envVars()) {
                    command.add("--env");
                    command.add(env);
                }
                
                for (Map.Entry<String, String> label : config.labels().entrySet()) {
                    command.add("--label");
                    command.add(label.getKey() + "=" + label.getValue());
                }
                
                command.add(config.podmanImage());

                log.infof("Starting Podman MCP server: %s with image %s", config.serverName(), config.podmanImage());
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);
                Process process = pb.start();

                activeProcesses.put(config.serverName(), process);
                writers.put(config.serverName(), process.getOutputStream());
                readers.put(config.serverName(), new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));

                // Step 1: Initialize
                ObjectNode initParams = objectMapper.createObjectNode();
                initParams.put("protocolVersion", "2025-03-26");
                initParams.set("capabilities", objectMapper.createObjectNode());
                ObjectNode clientInfo = objectMapper.createObjectNode();
                clientInfo.put("name", "arcclaw");
                clientInfo.put("version", "1.0");
                initParams.set("clientInfo", clientInfo);

                sendRequest(config.serverName(), "initialize", initParams);
                // Wait for init response
                readResponse(config.serverName());

                // Step 2: Initialized notification
                sendNotification(config.serverName(), "notifications/initialized", objectMapper.createObjectNode());

                // Step 3: List tools
                JsonNode toolsResponse = sendRequest(config.serverName(), "tools/list", objectMapper.createObjectNode());
                registerTools(config, toolsResponse);

                sandboxCircuitBreaker.recordSuccess();
                log.infof("Connected to MCP server: %s", config.serverName());
                return null;
            } catch (IOException e) {
                sandboxCircuitBreaker.recordFailure(sandboxCircuitBreaker.getState());
                throw new RuntimeException("Failed to connect to MCP server: " + config.serverName(), e);
            }
        }).replaceWithVoid();
    }

    private void registerTools(McpServerConfig config, JsonNode toolsResponse) {
        JsonNode tools = toolsResponse.get("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode tool : tools) {
                String name = tool.get("name").asText();
                String desc = tool.has("description") ? tool.get("description").asText() : "";
                String schema = tool.has("inputSchema") ? tool.get("inputSchema").toString() : "{}";
                
                DynamicToolCapability cap = new DynamicToolCapability(
                    name, desc, config.serverName(), McpTransportType.STDIO, false, schema
                );
                registry.register(cap);
            }
        }
    }

    public Uni<String> invoke(String serverName, String toolName, String paramsJson) {
        return Uni.createFrom().item(() -> {
            try {
                ObjectNode params = objectMapper.createObjectNode();
                params.put("name", toolName);
                if (paramsJson != null && !paramsJson.isBlank()) {
                    params.set("arguments", objectMapper.readTree(paramsJson));
                } else {
                    params.set("arguments", objectMapper.createObjectNode());
                }

                JsonNode result = sendRequest(serverName, "tools/call", params);
                return objectMapper.writeValueAsString(result);
            } catch (IOException e) {
                throw new RuntimeException("Failed to invoke dynamic tool: " + toolName + " on server: " + serverName, e);
            }
        });
    }

    public Uni<Void> disconnect(String serverName) {
        return Uni.createFrom().item(() -> {
            Process p = activeProcesses.remove(serverName);
            if (p != null) {
                p.destroy();
            }
            try {
                OutputStream w = writers.remove(serverName);
                if (w != null) w.close();
                BufferedReader r = readers.remove(serverName);
                if (r != null) r.close();
            } catch (IOException e) {
                log.warnf("Error closing streams for MCP server %s: %s", serverName, e.getMessage());
            }
            return null;
        }).replaceWithVoid();
    }

    private JsonNode sendRequest(String serverName, String method, ObjectNode params) throws IOException {
        int id = requestIdGenerator.getAndIncrement();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);

        writeLine(serverName, request);
        JsonNode response = readResponse(serverName);
        
        if (response.has("error")) {
            throw new RuntimeException("MCP server error: " + response.get("error").toString());
        }
        return response.get("result");
    }

    private void sendNotification(String serverName, String method, ObjectNode params) throws IOException {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        writeLine(serverName, notification);
    }

    private void writeLine(String serverName, JsonNode node) throws IOException {
        OutputStream out = writers.get(serverName);
        if (out == null) throw new IOException("No writer for server: " + serverName);

        String json = objectMapper.writeValueAsString(node);
        NdjsonWriter.writeLine(out, json);
    }

    private JsonNode readResponse(String serverName) throws IOException {
        BufferedReader reader = readers.get(serverName);
        if (reader == null) throw new IOException("No reader for server: " + serverName);
        
        String line = reader.readLine();
        if (line == null) throw new IOException("MCP server closed connection unexpectedly");
        return objectMapper.readTree(line);
    }

    @PreDestroy
    void cleanup() {
        log.info("PodmanMcpTransport shutting down — terminating active MCP processes");
        activeProcesses.forEach((sessionId, process) -> {
            log.infof("Destroying MCP process for session: %s", sessionId);
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    log.warnf("Process for session %s did not exit in 3s — forcibly killing", sessionId);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        });
        activeProcesses.clear();
        log.info("PodmanMcpTransport cleanup complete");
    }
}
