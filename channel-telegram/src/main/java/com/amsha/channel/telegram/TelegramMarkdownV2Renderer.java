package com.amsha.channel.telegram;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.stream.Collectors;

import com.amsha.channel.api.ApprovalPrompt;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ContentNode;
import com.amsha.channel.api.InteractionNode;
import com.amsha.channel.api.MarkdownEscapeService;
import com.amsha.channel.api.MarkdownProfile;
import com.amsha.channel.api.ReplyNode;
import com.amsha.channel.api.ReplyRenderer;
import com.amsha.common.util.HtmlArtifactUtil;

import org.jboss.logging.Logger;

/**
 * Renders a list of {@link ReplyNode} into Telegram's MarkdownV2 format.
 * <p>
 * Mandatory escaping: '_', '*', '[', ']', '(', ')', '~', '`', '>', '#', '+', '-', '=', '|', '{', '}', '.', '!'
 * must be escaped with a backslash in all parts of the message EXCEPT:
 * 1. Inside {@code pre} (CodeBlock) and {@code code} (InlineCode) entities.
 * 2. Inside the text part of {@code [...]()} (InlineLink) - though not used in production.
 */
@ApplicationScoped
public class TelegramMarkdownV2Renderer implements ReplyRenderer {

    private static final int MAX_APPROVAL_PARAMS_LENGTH = 3800;
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
        return nodes.stream().map(this::renderContentNode).collect(Collectors.joining("\n\n"));
    }

    @Override
    public String renderInteraction(InteractionNode node) {
        if (node instanceof ApprovalPrompt ap) {
            String params = ap.paramsJson();
            if (params == null) params = "{}";
            if (params.length() > MAX_APPROVAL_PARAMS_LENGTH) {
                params = params.substring(0, MAX_APPROVAL_PARAMS_LENGTH) + "\n... (truncated)";
            }
            params = escapeCodeFenceContent(params);
            return "⚠️ *APPROVAL REQUIRED*\n\n"
                    + "*Tool:* `" + esc(collapse(ap.toolName())) + "`\n"
                    + "*Session:* `" + esc(collapse(ap.sessionId())) + "`\n\n"
                    + "```json\n" + params + "\n```\n\n"
                    + "Reply with `/approve` or `/deny`";
        }
        return esc(HtmlArtifactUtil.collapseHtmlArtifact(node.fallbackText()));
    }

    private String renderContentNode(ContentNode node) {
        return switch (node) {
            case ContentNode.Text t -> esc(HtmlArtifactUtil.collapseHtmlArtifact(t.content()));
            case ContentNode.Heading h ->
                h.level() == 1 ? "*__" + esc(collapse(h.text())) + "__*" : "*" + esc(collapse(h.text())) + "*";
            case ContentNode.CodeBlock cb -> {
                if (HtmlArtifactUtil.looksLikeHtmlArtifact(cb.code())) {
                    yield esc(collapse(cb.code()));
                }
                String code = cb.code();
                String lang = cb.language() != null ? cb.language().trim() : "";
                yield "```" + escapeCodeFenceContent(lang) + "\n" + escapeCodeFenceContent(code) + "\n```";
            }
            case ContentNode.InlineCode ic -> "`" + escapeCodeFenceContent(collapse(ic.code())) + "`";
            case ContentNode.BulletList bl ->
                bl.items().stream()
                        .map(item -> "• " + esc(HtmlArtifactUtil.collapseHtmlArtifact(item)))
                        .collect(Collectors.joining("\n"));
            case ContentNode.NumberedList nl -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nl.items().size(); i++) {
                    sb.append(i + 1)
                            .append("\\. ")
                            .append(esc(HtmlArtifactUtil.collapseHtmlArtifact(
                                    nl.items().get(i))));
                    if (i < nl.items().size() - 1) sb.append("\n");
                }
                yield sb.toString();
            }
            case ContentNode.KeyValue kv ->
                "*" + esc(collapse(kv.key())) + ":* " + esc(HtmlArtifactUtil.collapseHtmlArtifact(kv.value()));
            case ContentNode.Separator _ -> "---";
            case ContentNode.ErrorMessage em ->
                "❌ *ERROR [" + esc(collapse(em.category())) + "]*\n\n" + esc(collapse(em.reason()));
            default -> {
                log.warnf(
                        "Unsupported ContentNode type %s in Telegram renderer",
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

    private static String collapse(String text) {
        return HtmlArtifactUtil.collapseHtmlArtifact(text);
    }

    /**
     * Keeps untrusted content from closing Telegram's MarkdownV2 code entity.
     * Telegram's parser requires literal backticks and backslashes inside code entities
     * to be escaped while retaining their displayed value.
     */
    private static String escapeCodeFenceContent(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("`", "\\`");
    }
}
