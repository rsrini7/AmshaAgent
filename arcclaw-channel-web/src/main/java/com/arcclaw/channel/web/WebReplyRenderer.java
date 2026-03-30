package com.arcclaw.channel.web;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.channel.api.ReplyRenderer;

@ApplicationScoped
public class WebReplyRenderer implements ReplyRenderer {

    @Override
    public ChannelType supports() {
        return ChannelType.WEBSOCKET;
    }

    @Override
    public String render(List<ReplyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (ReplyNode node : nodes) {
            sb.append(renderNode(node));
        }
        return sb.toString().trim();
    }

    private String renderNode(ReplyNode node) {
        return switch (node) {
            case ReplyNode.Text t -> t.content() + "\n\n";
            case ReplyNode.Heading h -> {
                String prefix = "#".repeat(Math.max(1, Math.min(h.level(), 6)));
                yield prefix + " " + h.text() + "\n\n";
            }
            case ReplyNode.CodeBlock cb -> {
                String lang = cb.language() != null ? cb.language() : "";
                yield "```" + lang + "\n" + cb.code() + "\n```\n\n";
            }
            case ReplyNode.InlineCode ic -> "`" + ic.code() + "`";
            case ReplyNode.BulletList bl -> bl.items().stream()
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
            case ReplyNode.KeyValue kv -> "**" + kv.key() + "**: " + kv.value() + "\n\n";
            case ReplyNode.Separator _ -> "---\n\n";
            case ReplyNode.ApprovalPrompt ap -> {
                yield "**Approval Required:**\n" +
                      "- **Tool:** `" + ap.toolName() + "`\n" +
                      "- **Params:**\n```json\n" + ap.paramsJson() + "\n```\n\n";
            }
            case ReplyNode.ErrorMessage err -> "**Error (" + err.category() + ")**: " + err.reason() + "\n\n";
        };
    }
}
