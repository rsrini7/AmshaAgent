package com.arcclaw.channel.telegram;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import com.arcclaw.channel.api.ApprovalPrompt;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ContentNode;
import com.arcclaw.channel.api.InteractionNode;
import com.arcclaw.channel.api.MarkdownEscapeService;
import com.arcclaw.channel.api.MarkdownProfile;
import com.arcclaw.channel.api.ReplyNode;
import com.arcclaw.channel.api.ReplyRenderer;
import org.jboss.logging.Logger;

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

    private static final Logger log = Logger.getLogger(TelegramMarkdownV2Renderer.class);

    private final MarkdownEscapeService escapeService = new MarkdownEscapeService();

    @Override
    public ChannelType supports() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public String renderContent(List<ContentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return "";
        }
        return nodes.stream()
                .map(this::renderContentNode)
                .collect(Collectors.joining("\n\n"));
    }

    @Override
    public String renderInteraction(InteractionNode node) {
        if (node instanceof ApprovalPrompt ap) {
            String params = ap.paramsJson();
            if (params == null) params = "{}";
            int maxJsonLen = 3800;
            if (params.length() > maxJsonLen) {
                params = params.substring(0, maxJsonLen) + "\n... (truncated)";
            }
            return "⚠️ *APPROVAL REQUIRED*\n\n"
                    + "*Tool:* `" + esc(ap.toolName()) + "`\n"
                    + "*Session:* `" + esc(ap.sessionId()) + "`\n\n"
                    + "```json\n" + params + "\n```\n\n"
                    + "Reply with `/approve` or `/deny`";
        }
        return esc(node.fallbackText());
    }

    private String renderContentNode(ContentNode node) {
        return switch (node) {
            case ContentNode.Text t -> esc(t.content());
            case ContentNode.Heading h -> h.level() == 1
                    ? "*__" + esc(h.text()) + "__*"
                    : "*" + esc(h.text()) + "*";
            case ContentNode.CodeBlock cb -> {
                String lang = cb.language() != null ? cb.language().trim() : "";
                yield "```" + lang + "\n" + cb.code() + "\n```";
            }
            case ContentNode.InlineCode ic -> "`" + ic.code() + "`";
            case ContentNode.BulletList bl -> bl.items().stream()
                    .map(item -> "• " + esc(item))
                    .collect(Collectors.joining("\n"));
            case ContentNode.NumberedList nl -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nl.items().size(); i++) {
                    sb.append(i + 1).append("\\. ").append(esc(nl.items().get(i)));
                    if (i < nl.items().size() - 1) sb.append("\n");
                }
                yield sb.toString();
            }
            case ContentNode.KeyValue kv -> "*" + esc(kv.key()) + ":* " + esc(kv.value());
            case ContentNode.Separator _ -> "---";
            case ContentNode.ErrorMessage em -> "❌ *ERROR [" + esc(em.category()) + "]*\n\n" + esc(em.reason());
            default -> {
                log.warnf("Unsupported ContentNode type %s in Telegram renderer",
                    node.getClass().getName());
                yield esc("[Unsupported content type]");
            }
        };
    }

    /**
     * Escapes mandatory characters for Telegram MarkdownV2.
     */
    String esc(String text) {
        return escapeService.escape(text, MarkdownProfile.TELEGRAM_V2);
    }
}
