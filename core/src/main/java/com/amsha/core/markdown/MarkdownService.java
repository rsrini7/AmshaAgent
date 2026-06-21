package com.amsha.core.markdown;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * Server-side Markdown rendering service.
 * <p>
 * Uses commonmark-java with GFM table extension. Provides HTML rendering
 * (for WebSocket / chat UI) and plain-text rendering (for Telegram, audit logs).
 */
@ApplicationScoped
public class MarkdownService {

    private final Parser parser;
    private final HtmlRenderer htmlRenderer;
    private final TextContentRenderer textRenderer;

    public MarkdownService() {
        List<Extension> extensions = List.of(TablesExtension.create());
        parser = Parser.builder().extensions(extensions).build();
        htmlRenderer = HtmlRenderer.builder().extensions(extensions).build();
        textRenderer = TextContentRenderer.builder().build();
    }

    /**
     * Renders Markdown to sanitized HTML fragment (no {@code <html>/<body>} wrapper).
     */
    public String renderToHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Node doc = parser.parse(markdown);
        return htmlRenderer.render(doc);
    }

    /**
     * Renders Markdown to plain text, stripping all formatting.
     * Useful for channels that don't support rich text (e.g. SMS, fallback).
     */
    public String renderToText(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Node doc = parser.parse(markdown);
        return textRenderer.render(doc);
    }

    /**
     * Returns the abstract syntax tree for a markdown document.
     * Can be used for custom traversals, metadata extraction, etc.
     */
    public Node parse(String markdown) {
        return parser.parse(markdown);
    }

    /**
     * Extracts the first heading (level 1) from a markdown document, or null.
     */
    public String extractTitle(String markdown) {
        if (markdown == null || markdown.isBlank()) return null;
        Node doc = parser.parse(markdown);
        return extractFirstHeading(doc);
    }

    private static String extractFirstHeading(Node node) {
        Node current = node.getFirstChild();
        while (current != null) {
            if (current instanceof Heading heading && heading.getLevel() == 1) {
                Node text = heading.getFirstChild();
                if (text instanceof Text t) return t.getLiteral();
                return null;
            }
            String found = extractFirstHeading(current);
            if (found != null) return found;
            current = current.getNext();
        }
        return null;
    }
}
