package com.arcclaw.app.cli;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.channel.api.ReplyRenderer;

/**
 * CLI implementation of {@link ReplyRenderer}.
 * Converts ReplyNode trees to plain-text terminal output.
 */
@ApplicationScoped
public class CliReplyRenderer implements ReplyRenderer {

    @Override
    public ChannelType supports() {
        return ChannelType.CLI;
    }

    @Override
    public String render(List<ReplyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ReplyNode node : nodes) {
            sb.append(renderNode(node));
        }
        return sb.toString().trim();
    }

    private String renderNode(ReplyNode node) {
        return switch (node) {
            case ReplyNode.Text t         -> t.content() + "\n\n";
            case ReplyNode.Heading h      -> h.text() + "\n\n";
            case ReplyNode.CodeBlock cb   -> cb.code() + "\n\n";
            case ReplyNode.InlineCode ic  -> ic.code();
            case ReplyNode.BulletList bl  -> bl.items().stream()
                    .map(item -> "- " + item)
                    .collect(Collectors.joining("\n")) + "\n\n";
            case ReplyNode.NumberedList nl -> {
                StringBuilder lst = new StringBuilder();
                for (int i = 0; i < nl.items().size(); i++) {
                    lst.append((i + 1)).append(". ").append(nl.items().get(i)).append("\n");
                }
                lst.append("\n");
                yield lst.toString();
            }
            case ReplyNode.KeyValue kv    -> kv.key() + ": " + kv.value() + "\n\n";
            case ReplyNode.Separator _    -> "---\n\n";
            case ReplyNode.ApprovalPrompt ap -> "[Approval Required] Tool: " + ap.toolName() + "\n\n";
            case ReplyNode.ErrorMessage err  -> "[Error] " + err.reason() + "\n\n";
        };
    }
}
