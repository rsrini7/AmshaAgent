package com.arcclaw.channel.web;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ApprovalPrompt;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ContentNode;
import com.arcclaw.channel.api.InteractionNode;
import com.arcclaw.channel.api.ReplyRenderer;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WebReplyRenderer implements ReplyRenderer {

    private static final Logger log = Logger.getLogger(WebReplyRenderer.class);

    @Override
    public ChannelType supports() {
        return ChannelType.WEBSOCKET;
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
            String tool = ap.toolName();
            if (tool != null && tool.startsWith(InteractionNode.PARALLEL_SENTINEL_PIPE_PREFIX)) {
                return "⏸ **Parallel execution requires your approval**\n\n";
            }
            return "⏸ **Tool execution requires your approval:** `" + tool + "`\n\n";
        }
        return node.fallbackText();
    }

    private String renderContentNode(ContentNode node) {
        return switch (node) {
            case ContentNode.Text t -> t.content() + "\n\n";
            case ContentNode.Heading h -> {
                String prefix = "#".repeat(Math.max(1, Math.min(h.level(), 6)));
                yield prefix + " " + h.text() + "\n\n";
            }
            case ContentNode.CodeBlock cb -> {
                String lang = cb.language() != null ? cb.language() : "";
                yield "```" + lang + "\n" + cb.code() + "\n```\n\n";
            }
            case ContentNode.InlineCode ic -> "`" + ic.code() + "`";
            case ContentNode.BulletList bl -> bl.items().stream()
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
            case ContentNode.KeyValue kv -> "**" + kv.key() + "**: " + kv.value() + "\n\n";
            case ContentNode.Separator _ -> "---\n\n";
            case ContentNode.ErrorMessage err -> "**Error (" + err.category() + ")**: " + err.reason() + "\n\n";
            default -> {
                log.warnf("Unsupported ContentNode type %s in Web renderer",
                    node.getClass().getName());
                yield "[Unsupported content type]";
            }
        };
    }
}
