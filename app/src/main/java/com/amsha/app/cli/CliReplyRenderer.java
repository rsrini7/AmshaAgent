package com.amsha.app.cli;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.stream.Collectors;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ContentNode;
import com.amsha.channel.api.InteractionNode;
import com.amsha.channel.api.ReplyRenderer;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Color;
import org.jboss.logging.Logger;

/**
 * CLI implementation of {@link ReplyRenderer}.
 * Converts ReplyNode trees to terminal output with optional ANSI color support.
 * ANSI colors are applied only when Jansi detects a supporting terminal.
 */
@ApplicationScoped
public class CliReplyRenderer implements ReplyRenderer {

    private static final Logger log = Logger.getLogger(CliReplyRenderer.class);

    @Override
    public ChannelType supports() {
        return ChannelType.CLI;
    }

    @Override
    public String renderContent(List<ContentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ContentNode node : nodes) {
            sb.append(renderContentNode(node));
        }
        return sb.toString().trim();
    }

    @Override
    public String renderInteraction(InteractionNode node) {
        if (node instanceof ApprovalPrompt ap) {
            return "[Approval Required] Tool: " + ap.toolName();
        }
        return node.fallbackText();
    }

    private String renderContentNode(ContentNode node) {
        return switch (node) {
            case ContentNode.Text t -> t.content() + "\n\n";
            case ContentNode.Heading h -> heading(h.text());
            case ContentNode.CodeBlock cb -> code(cb.code());
            case ContentNode.InlineCode ic -> codeInline(ic.code());
            case ContentNode.BulletList bl ->
                bl.items().stream().map(item -> bullet("- ") + item).collect(Collectors.joining("\n")) + "\n\n";
            case ContentNode.NumberedList nl -> {
                StringBuilder lst = new StringBuilder();
                for (int i = 0; i < nl.items().size(); i++) {
                    lst.append(bullet((i + 1) + ". "));
                    lst.append(nl.items().get(i)).append("\n");
                }
                lst.append("\n");
                yield lst.toString();
            }
            case ContentNode.KeyValue kv -> bold(kv.key()) + ": " + kv.value() + "\n\n";
            case ContentNode.Separator _ -> "---\n\n";
            case ContentNode.ErrorMessage err -> error("[Error] ") + err.reason() + "\n\n";
            default -> {
                log.warnf(
                        "Unsupported ContentNode type %s in CLI renderer",
                        node.getClass().getName());
                yield "[Unsupported content type]";
            }
        };
    }

    // -- ANSI helpers: apply color only when terminal supports it --

    private static String heading(String text) {
        if (!Ansi.isEnabled()) return text + "\n\n";
        return Ansi.ansi().bold().fg(Color.CYAN).a(text).reset().toString() + "\n\n";
    }

    private static String code(String text) {
        if (!Ansi.isEnabled()) return text + "\n\n";
        return Ansi.ansi().fg(Color.YELLOW).a(text).reset().toString() + "\n\n";
    }

    private static String codeInline(String text) {
        if (!Ansi.isEnabled()) return text;
        return Ansi.ansi().fg(Color.YELLOW).a(text).reset().toString();
    }

    private static String bullet(String marker) {
        if (!Ansi.isEnabled()) return marker;
        return Ansi.ansi().fg(Color.GREEN).a(marker).reset().toString();
    }

    private static String bold(String text) {
        if (!Ansi.isEnabled()) return text;
        return Ansi.ansi().bold().a(text).reset().toString();
    }

    private static String error(String text) {
        if (!Ansi.isEnabled()) return text;
        return Ansi.ansi().bold().fg(Color.RED).a(text).reset().toString();
    }
}
