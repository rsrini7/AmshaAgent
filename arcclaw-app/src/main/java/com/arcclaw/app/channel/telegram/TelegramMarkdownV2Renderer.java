package com.arcclaw.app.channel.telegram;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.channel.api.ReplyRenderer;

/**
 * Renders a list of {@link ReplyNode} into Telegram's MarkdownV2 format.
 * <p>
 * Mandatory escaping: '_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!'
 * must be escaped with a backslash in all parts of the message EXCEPT:
 * 1. Inside {@code pre} (CodeBlock) and {@code code} (InlineCode) entities.
 * 2. Inside the text part of {@code [...]()} (InlineLink) - though not used in Phase 47.
 */
@ApplicationScoped
public class TelegramMarkdownV2Renderer implements ReplyRenderer {

    @Override
    public ChannelType supports() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public String render(List<ReplyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        return nodes.stream()
                .map(this::renderNode)
                .collect(Collectors.joining("\n\n"));
    }

    private String renderNode(ReplyNode node) {
        return switch (node) {
            case ReplyNode.Text t -> esc(t.content());
            case ReplyNode.Heading h -> h.level() == 1
                    ? "*__" + esc(h.text()) + "__*"
                    : "*" + esc(h.text()) + "*";
            case ReplyNode.CodeBlock cb -> {
                // Language identifier is NOT MarkdownV2 content — do NOT escape it.
                // Telegram uses it as a literal syntax hint: ```java, ```c++, etc.
                String lang = cb.language() != null ? cb.language().trim() : "";
                yield "```" + lang + "\n" + cb.code() + "\n```";
            }
            case ReplyNode.InlineCode ic -> "`" + ic.code() + "`";
            case ReplyNode.BulletList bl -> bl.items().stream()
                    .map(item -> "• " + esc(item))
                    .collect(Collectors.joining("\n"));
            case ReplyNode.NumberedList nl -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nl.items().size(); i++) {
                    sb.append(i + 1).append("\\. ").append(esc(nl.items().get(i)));
                    if (i < nl.items().size() - 1) sb.append("\n");
                }
                yield sb.toString();
            }
            case ReplyNode.KeyValue kv -> "*" + esc(kv.key()) + ":* " + esc(kv.value());
            case ReplyNode.Separator _ -> "———";
            case ReplyNode.ApprovalPrompt ap -> {
                yield "⚠️ *APPROVAL REQUIRED*\n\n" +
                        "*Tool:* `" + esc(ap.toolName()) + "`\n" +
                        "*Session:* `" + esc(ap.sessionId()) + "`\n\n" +
                        "```json\n" + ap.paramsJson() + "\n```\n\n" +
                        "Reply with `/approve` or `/deny`";
            }
            case ReplyNode.ErrorMessage em -> {
                yield "❌ *ERROR [" + esc(em.category()) + "]*\n\n" + esc(em.reason());
            }
        };
    }

    private static final java.util.Set<Character> ESCAPE_CHARS = java.util.Set.of(
        '_', '*', '[', ']', '(', ')', '~', '`', '>',
        '#', '+', '-', '=', '|', '{', '}', '.', '!'
    );

    /**
     * Escapes mandatory characters for Telegram MarkdownV2.
     */
    String esc(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { sb.append('\\').append('\\'); continue; }
            if (ESCAPE_CHARS.contains(c)) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }
}
