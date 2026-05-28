package com.arcclaw.tool.playwright;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Inject
    BeanManager beanManager;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "arcclaw.tools.playwright.mcp-enabled", defaultValue = "false")
    boolean mcpEnabled;

    @ConfigProperty(name = "arcclaw.tools.playwright.mcp-headless", defaultValue = "true")
    boolean mcpHeadless;

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

    @SuppressWarnings("unchecked")
    private Object resolveService() {
        if (mcpService != null) return mcpService;
        Set<Bean<?>> beans = beanManager.getBeans(Object.class);
        log.infof("[MCP] BeanManager returned %d beans total", beans.size());
        for (Bean<?> bean : beans) {
            String className = bean.getBeanClass().getName();
            if (className.contains("McpClientService")) {
                log.infof("[MCP] Found bean by class name: %s", className);
                try {
                    Bean<Object> typed = (Bean<Object>) bean;
                    Object ref = beanManager.getReference(
                            typed, typed.getBeanClass(), beanManager.createCreationalContext(typed));
                    ref.getClass().getMethod("connectStdio", String.class, String.class, List.class, Map.class);
                    mcpService = ref;
                    log.infof("[MCP] Resolved McpClientService via BeanManager: %s", className);
                    return ref;
                } catch (NoSuchMethodException e) {
                    log.warnf("[MCP] Bean %s matched name but has no connectStdio method", className);
                } catch (Exception e) {
                    log.warnf("[MCP] Error resolving McpClientService: %s", LogUtil.rootMessage(e));
                }
            }
        }
        log.infof("[MCP] McpClientService not found in %d beans", beans.size());
        return null;
    }

    public void ensureConnected() {
        if (!mcpEnabled || attempted) return;
        attempted = true;
        log.infof("[MCP] mcpEnabled=%s, command=%s, args=%s", mcpEnabled, mcpCommand, mcpArgsRaw);
        Object service = resolveService();
        if (service == null) {
            log.info("[MCP] McpClientService not available — MCP path unavailable, will use CLI");
            return;
        }
        log.infof("[MCP] Connecting to Playwright MCP Server: %s %s", mcpCommand, parseArgs(mcpArgsRaw));
        try {
            var method =
                    service.getClass().getMethod("connectStdio", String.class, String.class, List.class, Map.class);
            Map<String, String> env =
                    mcpCommand.contains("npx") && mcpHeadless ? Map.of("PLAYWRIGHT_MCP_HEADLESS", "1") : Map.of();
            method.invoke(service, MCP_SERVER_NAME, mcpCommand, parseArgs(mcpArgsRaw), env);
            connected = true;
            log.info("[MCP] Playwright MCP Server connected successfully");
        } catch (Exception e) {
            log.warnf("[MCP] Connection to Playwright MCP Server failed, will use CLI: %s", LogUtil.rootMessage(e));
        }
    }

    public ToolResponse invoke(ToolExecutionContext ctx, Map<String, Object> params) {
        Object service = resolveService();
        if (service == null) {
            log.warn("[MCP] invoke() called but McpClientService not available");
            throw new RuntimeException("McpClientService not available");
        }
        try {
            String action = params.getOrDefault("action", "extract_text").toString();
            String mcpTool = toMcpToolName(action);
            if (mcpTool == null) {
                return ToolResponse.failure(400, "Unsupported action for MCP: " + action);
            }
            log.infof("[MCP] Invoking tool: %s → MCP %s", action, mcpTool);
            var invoke = service.getClass().getMethod("invoke", String.class, String.class, String.class);
            String paramsJson = objectMapper.writeValueAsString(params);
            String result = (String) invoke.invoke(service, MCP_SERVER_NAME, mcpTool, paramsJson);
            log.infof("[MCP] Tool %s succeeded (%d chars)", mcpTool, result != null ? result.length() : 0);
            return ToolResponse.success(result);
        } catch (Exception e) {
            log.warnf("[MCP] Tool invocation failed: %s", LogUtil.rootMessage(e));
            throw new RuntimeException("MCP invocation failed", e);
        }
    }

    /**
     * Maps ArcClaw action names to official Playwright MCP tool names.
     * extract_text maps to browser_navigate which returns page text content,
     * not browser_snapshot (which returns a minimal accessibility tree).
     */
    private static String toMcpToolName(String action) {
        return switch (action) {
            case "navigate", "extract_text" -> "browser_navigate";
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
                if (!cur.isEmpty()) {
                    args.add(cur.toString());
                    cur = new StringBuilder();
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) args.add(cur.toString());
        return args;
    }
}
