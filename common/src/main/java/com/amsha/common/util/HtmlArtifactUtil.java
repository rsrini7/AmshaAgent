package com.amsha.common.util;

import java.util.List;
import java.util.Locale;

/**
 * Shared detection for generated HTML artifacts that should be opened in a browser
 * instead of being echoed back into chat.
 */
public final class HtmlArtifactUtil {

    public static final String HTML_ARTIFACT_NOTICE =
            "[HTML artifact generated - open the browser preview to view it.]";
    public static final String HTML_TEMPLATE_NOTICE = "[HTML content omitted from chat.]";

    private static final List<String> HTML_MARKERS = List.of(
            "<!doctype html",
            "<html",
            "</html>",
            "<head",
            "</head>",
            "<body",
            "</body>",
            "<script",
            "</script>",
            "<canvas",
            "</canvas>");

    private HtmlArtifactUtil() {}

    public static boolean looksLikeHtmlArtifact(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String lower = text.strip().toLowerCase(Locale.ROOT);
        int markerHits = 0;
        for (String marker : HTML_MARKERS) {
            if (lower.contains(marker)) {
                markerHits++;
            }
        }

        return lower.startsWith("<!doctype html")
                || lower.startsWith("<html")
                || lower.contains("<html")
                || lower.contains("</html>")
                || markerHits >= 3;
    }

    public static String collapseHtmlArtifact(String text) {
        return collapseHtmlArtifact(text, null);
    }

    public static String collapseHtmlArtifact(String text, String command) {
        if (!looksLikeHtmlArtifact(text)) {
            return text;
        }
        if (isLikelyGeneratedHtmlCommand(command)) {
            return HTML_ARTIFACT_NOTICE;
        }
        return HTML_TEMPLATE_NOTICE;
    }

    public static boolean isLikelyGeneratedHtmlCommand(String command) {
        if (command == null || command.isBlank()) {
            return true;
        }

        String lower = command.strip().toLowerCase(Locale.ROOT);
        if (lower.contains("templates/")) {
            return lower.contains("output/");
        }

        return lower.startsWith("open ")
                || lower.startsWith("xdg-open ")
                || lower.startsWith("start ")
                || lower.contains("output/")
                || lower.contains("browser preview")
                || lower.contains("open in browser")
                || lower.contains("default browser")
                || lower.contains("xdg-open")
                || lower.contains(" open ")
                || lower.contains(" start ")
                || lower.contains("cat > output/")
                || lower.contains("tee output/")
                || lower.contains(">> output/");
    }
}
