package com.arcclaw.tool.playwright;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.tools.ToolExecutionContext;
import com.arcclaw.core.tools.ToolResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PlaywrightMcpHelper {

    private static final Logger log = Logger.getLogger(PlaywrightMcpHelper.class);

    private static final String MCP_SERVER_NAME = "playwright";

    @Inject @Any
    Instance<Object> allBeans;

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
    private Object mcpService = null;

    public boolean isAvailable() {
        return mcpEnabled && connected;
    }

    private Object resolveService() {
        if (mcpService != null) return mcpService;
        for (Object bean : allBeans) {
            try {
                bean.getClass().getMethod("connectStdio", String.class, String.class, List.class, Map.class);
                mcpService = bean;
                return bean;
            } catch (NoSuchMethodException e) {
                // not the MCP service — skip
            }
        }
        return null;
    }

    public void ensureConnected() {
        if (!mcpEnabled || attempted) return;
        attempted = true;
        Object service = resolveService();
        if (service == null) {
            log.debug("McpClientService not found — MCP disabled");
            return;
        }
        try {
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
        Object service = resolveService();
        if (service == null) {
            throw new RuntimeException("McpClientService not available");
        }
        try {
            String action = params.getOrDefault("action", "extract_text").toString();
            String mcpTool = toMcpToolName(action);
            if (mcpTool == null) {
                return ToolResponse.failure(400, "Unsupported action for MCP: " + action);
            }
            var invoke = service.getClass().getMethod("invoke", String.class, String.class, String.class);
            String paramsJson = objectMapper.writeValueAsString(params);
            String result = (String) invoke.invoke(service, MCP_SERVER_NAME, mcpTool, paramsJson);
            return ToolResponse.success(result);
        } catch (Exception e) {
            throw new RuntimeException("MCP invocation failed", e);
        }
    }

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
