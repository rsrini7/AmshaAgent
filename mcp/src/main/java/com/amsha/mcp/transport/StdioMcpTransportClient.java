package com.amsha.mcp.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.amsha.common.util.LogUtil;
import com.amsha.tool.api.ToolCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Transport client for local STDIO-based Model Context Protocol (MCP) servers.
 * Encapsulates process lifecycles, I/O streams, NDJSON framing, reconnects, and health checks.
 */
class StdioMcpTransportClient {

    record StdioConfig(
            String command, List<String> args, Map<String, String> env, ToolCategory category, String ownerUserId) {}

    @FunctionalInterface
    interface ToolRegistrationListener {
        void onToolsDiscovered(String serverName, JsonNode toolsResult, ToolCategory category, String ownerUserId);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder pb) throws IOException;
    }

    private static final Logger log = Logger.getLogger(StdioMcpTransportClient.class);
    private static final String METRIC_MCP_LOAD_FAILURE = "amsha.mcp.tool.load.failure";
    private static final String TAG_SERVER = "server";
    private static final String TAG_PHASE = "phase";

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Supplier<ManagedExecutor> executorSupplier;
    private final ToolRegistrationListener registrationListener;
    private final McpJsonRpcCodec jsonRpcCodec;
    private ProcessStarter processStarter = ProcessBuilder::start;

    private final ConcurrentHashMap<String, StdioConfig> stdioConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OutputStream> processWriters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedReader> processReaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> stdioLocks = new ConcurrentHashMap<>();
    private final Set<String> reconnectingServers = ConcurrentHashMap.newKeySet();

    private final AtomicInteger requestIdGen = new AtomicInteger(1);
    private ScheduledExecutorService healthCheckExecutor;

    StdioMcpTransportClient(
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Supplier<ManagedExecutor> executorSupplier,
            ToolRegistrationListener registrationListener,
            McpJsonRpcCodec jsonRpcCodec) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.executorSupplier = executorSupplier;
        this.registrationListener = registrationListener;
        this.jsonRpcCodec = jsonRpcCodec;
    }

    void init(long healthCheckIntervalMs) {
        if (healthCheckIntervalMs <= 0) return;
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-health-check");
            t.setDaemon(true);
            return t;
        });
        var _ = healthCheckExecutor.scheduleWithFixedDelay(
                this::healthCheck, healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS);
    }

    void healthCheck() {
        for (String serverName : processes.keySet()) {
            Process p = processes.get(serverName);
            if (p != null && !p.isAlive()) {
                log.warnf("Health check: MCP STDIO server '%s' process is dead, initiating reconnect", serverName);
                StdioConfig cfg = stdioConfigs.get(serverName);
                if (cfg != null) {
                    if (!reconnectingServers.add(serverName)) {
                        continue;
                    }
                    ManagedExecutor executor = executorSupplier != null ? executorSupplier.get() : null;
                    Runnable task = () -> {
                        ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, _ -> new ReentrantLock());
                        lock.lock();
                        try {
                            cleanupStdio(serverName);
                            Process current = processes.get(serverName);
                            if (current != null && current.isAlive()) {
                                log.infof("Process for '%s' already restored, skipping duplicate connect", serverName);
                                return;
                            }
                            connect(
                                    serverName,
                                    cfg.command(),
                                    cfg.args(),
                                    cfg.env(),
                                    cfg.category(),
                                    cfg.ownerUserId());
                        } catch (Exception e) {
                            log.errorf("Health check reconnect failed for %s: %s", serverName, LogUtil.rootMessage(e));
                        } finally {
                            lock.unlock();
                            reconnectingServers.remove(serverName);
                        }
                    };
                    if (executor != null) {
                        executor.execute(task);
                    } else {
                        task.run();
                    }
                } else {
                    ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, _ -> new ReentrantLock());
                    lock.lock();
                    try {
                        cleanupStdio(serverName);
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
    }

    JsonNode connect(
            String serverName,
            String command,
            List<String> args,
            Map<String, String> env,
            ToolCategory category,
            String ownerUserId) {
        stdioConfigs.put(serverName, new StdioConfig(command, args, env, category, ownerUserId));
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> fullCmd = new ArrayList<>();
            fullCmd.add(command);
            if (args != null) fullCmd.addAll(args);
            pb.command(fullCmd);
            if (env != null) pb.environment().putAll(env);

            log.infof("Starting local STDIO MCP server: %s (cmd: %s)", serverName, fullCmd);
            Process process = processStarter.start(pb);
            processes.put(serverName, process);
            processWriters.put(serverName, process.getOutputStream());
            processReaders.put(
                    serverName,
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));

            // 1. Initialize
            ObjectNode initParams = jsonRpcCodec.createInitParams();
            sendRequestStdio(serverName, "initialize", initParams);

            // 2. Initialized
            sendNotificationStdio(serverName, "notifications/initialized", objectMapper.createObjectNode());

            // 3. List tools
            JsonNode toolsResult = sendRequestStdio(serverName, "tools/list", objectMapper.createObjectNode());
            if (registrationListener != null) {
                registrationListener.onToolsDiscovered(serverName, toolsResult, category, ownerUserId);
            }
            return toolsResult;

        } catch (Exception e) {
            if (meterRegistry != null) {
                meterRegistry
                        .counter(METRIC_MCP_LOAD_FAILURE, TAG_SERVER, serverName, TAG_PHASE, "stdio_connect")
                        .increment();
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new McpConnectionException("Failed to connect to local MCP server via STDIO: " + serverName, e);
        }
    }

    String invoke(String serverName, String toolName, String paramsJson) {
        ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, _ -> new ReentrantLock());
        lock.lock();
        try {
            if (!processes.containsKey(serverName) && stdioConfigs.containsKey(serverName)) {
                log.infof("reconnecting dead MCP server: %s", serverName);
                StdioConfig cfg = stdioConfigs.get(serverName);
                connect(serverName, cfg.command(), cfg.args(), cfg.env(), cfg.category(), cfg.ownerUserId());
            }
        } finally {
            lock.unlock();
        }

        try {
            ObjectNode callParams = jsonRpcCodec.createCallParams(toolName, paramsJson);
            JsonNode result = sendRequestStdio(serverName, "tools/call", callParams);
            return jsonRpcCodec.extractTextResult(objectMapper.writeValueAsString(
                    objectMapper.createObjectNode().set("result", result)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke local tool via STDIO: " + toolName, e);
        }
    }

    boolean hasServer(String serverName) {
        return processes.containsKey(serverName);
    }

    boolean hasConfig(String serverName) {
        return stdioConfigs.containsKey(serverName);
    }

    void cleanup(String serverName) {
        cleanupStdio(serverName);
    }

    void close() {
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                healthCheckExecutor.shutdownNow();
            }
        }
        Set<String> allServers = new HashSet<>();
        allServers.addAll(processes.keySet());
        allServers.addAll(processWriters.keySet());
        allServers.addAll(processReaders.keySet());
        allServers.forEach(this::cleanupStdio);
        processes.clear();
    }

    void registerProcessForTesting(
            String serverName, Process process, OutputStream writer, BufferedReader reader, StdioConfig config) {
        if (process != null) processes.put(serverName, process);
        if (writer != null) processWriters.put(serverName, writer);
        if (reader != null) processReaders.put(serverName, reader);
        if (config != null) stdioConfigs.put(serverName, config);
    }

    Map<String, Process> getProcessesForTesting() {
        return Collections.unmodifiableMap(processes);
    }

    Map<String, StdioConfig> getConfigsForTesting() {
        return Collections.unmodifiableMap(stdioConfigs);
    }

    StdioConfig createConfig(
            String command, List<String> args, Map<String, String> env, ToolCategory category, String ownerUserId) {
        return new StdioConfig(command, args, env, category, ownerUserId);
    }

    void setProcessStarterForTesting(ProcessStarter processStarter) {
        this.processStarter = processStarter != null ? processStarter : ProcessBuilder::start;
    }

    private JsonNode sendRequestStdio(String serverName, String method, ObjectNode params) throws Exception {
        ReentrantLock lock = stdioLocks.computeIfAbsent(serverName, _ -> new ReentrantLock());
        lock.lock();
        try {
            int id = requestIdGen.getAndIncrement();
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            request.set("params", params);

            OutputStream out = processWriters.get(serverName);
            if (out == null) throw new IOException("No writer for MCP server: " + serverName);

            try {
                NdjsonWriter.writeLine(out, objectMapper.writeValueAsString(request));

                BufferedReader reader = processReaders.get(serverName);
                if (reader == null) throw new IOException("No reader for MCP server: " + serverName);
                String line = reader.readLine();
                if (line == null) throw new IOException("MCP server closed connection unexpectedly");

                JsonNode response = objectMapper.readTree(line);
                if (response.has("error")) {
                    throw new RuntimeException(
                            "MCP server error: " + response.get("error").toString());
                }
                return response.get("result");
            } catch (IOException e) {
                // F19 Dead Process Guard: cleanup on any transport failure
                // next retry loop will call connect via invoke() fallback
                log.warnf(
                        "MCP STDIO transport error for %s: %s - tearing down process state",
                        serverName, LogUtil.rootMessage(e));
                cleanupStdio(serverName);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private void sendNotificationStdio(String serverName, String method, ObjectNode params) throws Exception {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);

        OutputStream out = processWriters.get(serverName);
        if (out == null) return;
        NdjsonWriter.writeLine(out, objectMapper.writeValueAsString(notification));
    }

    private void cleanupStdio(String serverName) {
        try {
            OutputStream out = processWriters.remove(serverName);
            if (out != null) out.close();
        } catch (IOException e) {
            log.debugf("Failed to close MCP writer for %s: %s", serverName, LogUtil.rootMessage(e));
        }

        try {
            BufferedReader reader = processReaders.remove(serverName);
            if (reader != null) reader.close();
        } catch (IOException e) {
            log.debugf("Failed to close MCP reader for %s: %s", serverName, LogUtil.rootMessage(e));
        }

        Process p = processes.remove(serverName);
        if (p != null) {
            p.destroyForcibly();
            try {
                p.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
