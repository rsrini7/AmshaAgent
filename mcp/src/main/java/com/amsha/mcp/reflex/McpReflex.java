package com.amsha.mcp.reflex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Map;

import com.amsha.core.pipeline.MessageContext;
import com.amsha.core.reflex.AgentReflex;
import com.amsha.core.reflex.ReflexPriority;
import com.amsha.core.reflex.ReflexResult;
import com.amsha.mcp.bridge.McpToolBridge;
import com.amsha.mcp.tool.DynamicToolCapabilityRegistry;
import com.amsha.mcp.transport.McpApprovalService;
import com.amsha.mcp.transport.McpConnectionException;
import com.amsha.mcp.transport.McpManagementService;
import com.amsha.mcp.transport.McpSetupLinkService;
import com.amsha.mcp.transport.McpSetupTokenService;

/** Handles MCP status and safe lifecycle commands from chat and Telegram. */
@ApplicationScoped
@ReflexPriority(500)
public class McpReflex implements AgentReflex {

    @Inject
    DynamicToolCapabilityRegistry registry;

    @Inject
    McpToolBridge mcpToolBridge;

    @Inject
    McpManagementService managementService;

    @Inject
    McpSetupTokenService setupTokenService;

    @Inject
    McpSetupLinkService setupLinkService;

    @Override
    public boolean matches(String input) {
        if (input == null) return false;
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("/mcp") || normalized.startsWith("/mcp ");
    }

    @Override
    public ReflexResult execute(String sessionId, String userId, String chatId, String input, MessageContext context) {
        String[] args = input.trim().split("\\s+");
        if (args.length == 1) {
            return ReflexResult.respond(status(userId) + "\n\n" + help(), context);
        }
        if ("list".equalsIgnoreCase(args[1]) || "status".equalsIgnoreCase(args[1])) {
            return ReflexResult.respond(status(userId), context);
        }
        try {
            return ReflexResult.respond(executeCommand(userId, chatId, context, args), context);
        } catch (IllegalArgumentException | McpConnectionException e) {
            return ReflexResult.respond("❌ MCP operation failed: " + safeMessage(e), context);
        }
    }

    private String executeCommand(String userId, String chatId, MessageContext context, String[] args) {
        String command = args[1].toLowerCase(Locale.ROOT);
        return switch (command) {
            case "help" -> help();
            case "discover" -> {
                requireArgs(args, 4, "discover <name> <https-url>");
                yield pending(managementService.requestDiscovery(userId, args[2], args[3], Map.of()));
            }
            case "setup" -> {
                requireArgs(args, 3, "setup <name>");
                String token =
                        setupTokenService.issue(userId, channelType(context), channelId(context, chatId), args[2]);
                yield "🔐 Open this short-lived MCP setup link in the web UI: "
                        + "[Open link](" + setupLink(token) + ")"
                        + " (single-use; expires in 10 minutes).";
            }
            case "edit" -> {
                requireArgs(args, 3, "edit <name>");
                String token = setupTokenService.issueForEdit(
                        userId, channelType(context), channelId(context, chatId), args[2]);
                yield "🔐 Open this short-lived MCP edit link in web UI: "
                        + "[Open link](" + setupLink(token) + ")"
                        + " (single-use; expires in 10 minutes).";
            }
            case "connect" -> {
                requireArgs(args, 3, "connect <name> [https-url]");
                yield args.length >= 4
                        ? pending(managementService.requestConnect(userId, args[2], args[3], Map.of()))
                        : pending(managementService.requestReconnect(userId, args[2]));
            }
            case "activate" -> {
                requireArgs(args, 3, "activate <name>");
                yield pending(managementService.requestReconnect(userId, args[2]));
            }
            case "reconnect" -> {
                requireArgs(args, 3, "reconnect <name>");
                yield pending(managementService.requestReconnect(userId, args[2]));
            }
            case "pause" -> {
                requireArgs(args, 3, "pause <name>");
                yield format(managementService.pause(userId, args[2]), "Paused");
            }
            case "resume" -> {
                requireArgs(args, 3, "resume <name>");
                yield pending(managementService.requestReconnect(userId, args[2]));
            }
            case "approve" -> {
                requireArgs(args, 3, "approve <task-id>");
                yield formatApproval(managementService.approve(userId, args[2]));
            }
            case "deny" -> {
                requireArgs(args, 3, "deny <task-id>");
                yield managementService.rejectApproval(userId, args[2])
                        ? "✅ MCP approval denied."
                        : "⚠️ MCP approval task not found.";
            }
            case "tools" -> {
                requireArgs(args, 3, "tools <server>");
                var tools = managementService.listTools(userId, args[2]);
                yield tools.isEmpty()
                        ? "No discovered tools for MCP server `" + args[2] + "`."
                        : "🧰 MCP tools for `" + args[2] + "`:\n• "
                                + String.join(
                                        "\n• ",
                                        tools.stream()
                                                .map(McpManagementService.ToolPreview::name)
                                                .toList());
            }
            case "disconnect" -> {
                requireArgs(args, 3, "disconnect <name>");
                if (!managementService.disconnect(userId, args[2]))
                    throw new McpConnectionException("connection not found");
                yield "✅ Disconnected MCP server `" + args[2] + "`.";
            }
            case "enable" -> {
                requireArgs(args, 4, "enable <server> <tool>");
                yield pending(managementService.requestEnableTool(userId, args[2], args[3]));
            }
            case "disable" -> {
                requireArgs(args, 4, "disable <server> <tool>");
                yield format(managementService.disableTool(userId, args[2], args[3]), "Disabled");
            }
            default -> "Usage: /mcp help";
        };
    }

    private static String help() {
        return "🧰 MCP commands:\n"
                + "• /mcp — show status\n"
                + "• /mcp help — show this guide\n"
                + "• /mcp list — list managed servers\n"
                + "• /mcp setup <name> — open the UI to add a server\n"
                + "• /mcp edit <name> — open the UI to edit a server\n"
                + "• /mcp discover <name> <https-url> — preview tools\n"
                + "• /mcp connect <name> [https-url] — connect or reconnect\n"
                + "• /mcp tools <server> — list discovered tools\n"
                + "• /mcp enable <server> <tool> — request tool activation\n"
                + "• /mcp disable <server> <tool> — deactivate a tool\n"
                + "• /mcp pause|resume|reconnect|disconnect <server>\n"
                + "• /mcp approve <task-id> or /mcp deny <task-id>\n"
                + "\nCredentials and custom headers must be entered in the MCP tab, never in chat.";
    }

    private String status(String userId) {
        StringBuilder sb = new StringBuilder("🔌 *MCP Status*\n\n");
        sb.append("*Exposed internal tools* (")
                .append(mcpToolBridge.getExposedTools().size())
                .append("):\n");
        mcpToolBridge
                .getExposedTools()
                .forEach(t -> sb.append("• ").append(t.name()).append('\n'));
        if (managementService == null) {
            var dynamicTools = registry.getAll();
            if (dynamicTools.isEmpty()) sb.append("\n*Dynamic MCP tools*: none connected\n");
            else {
                sb.append("\n*Dynamic MCP tools* (").append(dynamicTools.size()).append("):\n");
                dynamicTools.forEach(tool -> sb.append("• ")
                        .append(tool.toolName())
                        .append(" [")
                        .append(tool.mcpServerName())
                        .append(", ")
                        .append(tool.transport())
                        .append("]\n"));
            }
        } else {
            var connections = managementService.list(userId);
            sb.append("\n*Managed servers* (").append(connections.size()).append("):\n");
            if (connections.isEmpty()) sb.append("• none\n");
            connections.forEach(connection -> sb.append("• `")
                    .append(connection.name())
                    .append("` — ")
                    .append(connection.state())
                    .append(" (")
                    .append(connection.enabledToolCount())
                    .append('/')
                    .append(connection.discoveredToolCount())
                    .append(" tools)\n"));
        }
        return sb.toString();
    }

    private static String format(McpManagementService.ConnectionSnapshot snapshot, String verb) {
        return "✅ "
                + verb
                + " MCP server `"
                + snapshot.name()
                + "` ("
                + snapshot.enabledToolCount()
                + "/"
                + snapshot.discoveredToolCount()
                + " tools enabled).";
    }

    private static String formatPreview(McpManagementService.DiscoveryPreview preview, String verb) {
        return "✅ " + verb + " `" + preview.name() + "`: " + preview.tools().size()
                + " tools discovered; none enabled.";
    }

    private static String pending(McpApprovalService.PendingApproval approval) {
        return "⏳ MCP approval required for "
                + approval.operation().toLowerCase(Locale.ROOT)
                + " `"
                + approval.name()
                + "`. Approve with `/mcp approve "
                + approval.taskId()
                + "` (expires in 10 minutes).";
    }

    private static String formatApproval(McpManagementService.ApprovalExecution execution) {
        if (execution.result() instanceof McpManagementService.DiscoveryPreview preview) {
            return formatPreview(preview, "Discovered");
        }
        if (execution.result() instanceof McpManagementService.ConnectionSnapshot snapshot) {
            return format(snapshot, execution.operation().toLowerCase(Locale.ROOT));
        }
        return "✅ MCP operation completed.";
    }

    private static String channelType(MessageContext context) {
        if (context != null && context.getChannelType() != null) {
            return context.getChannelType().id();
        }
        return "chat";
    }

    private static String channelId(MessageContext context, String chatId) {
        String candidate = context == null ? chatId : context.getChatId();
        return candidate == null || candidate.isBlank() ? "unknown" : candidate;
    }

    private String setupLink(String token) {
        return setupLinkService == null ? "/#/mcp/setup?token=" + token : setupLinkService.link(token);
    }

    private static void requireArgs(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("Usage: /mcp " + usage);
        if ("http".equalsIgnoreCase(args[args.length - 1]) || args[args.length - 1].contains("=")) {
            throw new IllegalArgumentException("Credentials must be configured in the MCP tab, never in chat");
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
