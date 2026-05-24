package com.arcclaw.tool.playwright;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Optional MCP transport helper for PlaywrightCliTool.
 * <p>
 * Uses reflection to invoke McpClientService so the arcclaw-mcp dependency
 * remains optional. This path is JVM-only — GraalVM native-image requires
 * explicit reflection registration for the MCP transport service methods.
 */
@ApplicationScoped
public class PlaywrightMcpHelper {

    private static final Logger log = Logger.getLogger(PlaywrightMcpHelper.class);

    private static final String MCP_SERVER_NAME = "playwright";

    @Inject
    Instance<Object> mcpClientService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.tools.playwright.mcp-enabled", defaultValue = "false")
    boolean mcpEnabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.mcp-command", defaultValue = "npx")
    String mcpCommand;

    @ConfigProperty(name = "arcclaw.tools.playwright.mcp-args", defaultValue = "@playwright/mcp")
    String mcpArgsRaw;

    private volatile boolean connected = false;
    private volatile boolean attempted = false;

    public boolean isAvailable() {
        return mcpEnabled && connected;
    }

    public void ensureConnected() {
        if (!mcpEnabled || attempted) return;
        attempted = true;
        if (!mcpClientService.isResolvable()) {
            log.debug("McpClientService not available — MCP disabled");
            return;
        }
        try {
            Object service = mcpClientService.get();
            var method = service.getClass().getMethod("connectStdio",
                    String.class, String.class, List.class, Map.class);
            method.invoke(service, MCP_SERVER_NAME, mcpCommand, parseArgs(mcpArgsRaw), Map.of());
            connected = true;
            log.info("Playwright MCP Server connected");
        } catch (Exception e) {
            log.warnf("MCP connection failed, will use CLI: %s", LogUtil.rootMessage(e));
        }
    }

    public ToolResponse invoke(ToolExecutionContext ctx, Map<String, Object> params) {
        if (!mcpClientService.isResolvable()) {
            throw new RuntimeException("McpClientService not available");
        }
        try {
            String action = params.getOrDefault("action", "extract_text").toString();
            String mcpTool = toMcpToolName(action);
            if (mcpTool == null) {
                return ToolResponse.failure(400, "Unsupported action for MCP: " + action);
            }
            Object service = mcpClientService.get();
            var invoke = service.getClass().getMethod("invoke", String.class, String.class, String.class);
            String paramsJson = objectMapper.writeValueAsString(params);
            String result = (String) invoke.invoke(service, MCP_SERVER_NAME, mcpTool, paramsJson);
            return ToolResponse.success(result);
        } catch (Exception e) {
            throw new RuntimeException("MCP invocation failed", e);
        }
    }

    /**
     * Maps ArcClaw action names to official Playwright MCP tool names.
     * extract_text maps to browser_snapshot (structured accessibility snapshot),
     * not raw DOM innerText — the CLI fallback handles raw text cases.
     */
    private static String toMcpToolName(String action) {
        return switch (action) {
            case "navigate" -> "browser_navigate";
            case "extract_text" -> "browser_snapshot";
            case "screenshot" -> "browser_take_screenshot";
            case "click" -> "browser_click";
            case "fill" -> "browser_type";
            case "wait" -> "browser_wait_for";
            case "eval" -> "browser_evaluate";
            default -> null;
        };
    }

    private static List<String> parseArgs(String raw) {
        List<String> args = new ArrayList<>();
        if (raw == null || raw.isBlank()) return args;
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\'') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (!cur.isEmpty()) { args.add(cur.toString()); cur = new StringBuilder(); }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) args.add(cur.toString());
        return args;
    }
}
