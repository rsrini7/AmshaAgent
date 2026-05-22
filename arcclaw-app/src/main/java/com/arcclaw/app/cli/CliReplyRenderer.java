package com.arcclaw.app.cli;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ApprovalPrompt;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ContentNode;
import com.arcclaw.channel.api.InteractionNode;
import com.arcclaw.channel.api.ReplyRenderer;
import org.jboss.logging.Logger;

/**
 * CLI implementation of {@link ReplyRenderer}.
 * Converts ReplyNode trees to plain-text terminal output.
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
            case ContentNode.Text t         -> t.content() + "\n\n";
            case ContentNode.Heading h      -> h.text() + "\n\n";
            case ContentNode.CodeBlock cb   -> cb.code() + "\n\n";
            case ContentNode.InlineCode ic  -> ic.code();
            case ContentNode.BulletList bl  -> bl.items().stream()
                    .map(item -> "- " + item)
                    .collect(Collectors.joining("\n")) + "\n\n";
            case ContentNode.NumberedList nl -> {
                StringBuilder lst = new StringBuilder();
                for (int i = 0; i < nl.items().size(); i++) {
                    lst.append((i + 1)).append(". ").append(nl.items().get(i)).append("\n");
                }
                lst.append("\n");
                yield lst.toString();
            }
            case ContentNode.KeyValue kv    -> kv.key() + ": " + kv.value() + "\n\n";
            case ContentNode.Separator _    -> "---\n\n";
            case ContentNode.ErrorMessage err  -> "[Error] " + err.reason() + "\n\n";
            default -> {
                log.warnf("Unsupported ContentNode type %s in CLI renderer",
                    node.getClass().getName());
                yield "[Unsupported content type]";
            }
        };
    }
}
