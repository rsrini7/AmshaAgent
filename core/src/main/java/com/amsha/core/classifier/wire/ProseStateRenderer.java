package com.amsha.core.classifier.wire;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Renders an allow-listed, redacted state projection as prose for providers whose encoder was
 * trained on natural language rather than on JSON.
 *
 * <p>CLM documents the rule this class implements: objects render as {@code key: value} text and
 * arrays as {@code - item} lines. Nesting is expressed by indenting two spaces per level, with a
 * container introduced by its key followed by a colon:
 *
 * <pre>
 * ticket: charged twice
 * customer:
 *   name: Ada
 *   tags:
 *     - vip
 *     - refund
 * </pre>
 *
 * <p>Two properties matter for review:
 *
 * <ul>
 *   <li><b>It cannot widen what leaves the process.</b> Rendering happens strictly <em>after</em>
 *       allow-list projection and redaction. The input is the same JSON string the
 *       {@link WireProfile#JSON_OBJECT} profile would have sent, so a field the projection dropped
 *       is absent here for the same reason, not because this class is trusted to drop it.</li>
 *   <li><b>It is deterministic and total.</b> Field order follows the projection's own order, and
 *       no value is invented, summarised, or reworded. A scalar is emitted verbatim; a text value
 *       containing newlines is emitted verbatim too, which can visually break a line without
 *       changing the fact that the whole state is one string.</li>
 * </ul>
 */
final class ProseStateRenderer {

    /** Two spaces per nesting level. */
    private static final String INDENT = "  ";

    private ProseStateRenderer() {}

    /**
     * Renders a state projection as prose.
     *
     * @param state the projected state; must be a parsed JSON node, not a string to be reparsed
     * @return the prose rendering, or an empty string when the projection carries no fields at all
     */
    static String render(JsonNode state) {
        List<String> lines = new ArrayList<>();
        append(state, "", lines);
        return String.join("\n", lines);
    }

    /**
     * Appends one node's lines, recursing into containers.
     *
     * @param node node to render
     * @param indent indentation prefix for this level
     * @param lines accumulator
     */
    private static void append(JsonNode node, String indent, List<String> lines) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value.isContainerNode()) {
                    lines.add(indent + field.getKey() + ":");
                    append(value, indent + INDENT, lines);
                } else {
                    lines.add(indent + field.getKey() + ": " + scalar(value));
                }
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isContainerNode()) {
                    // A container cannot sit on the "- " line itself; introduce it with a bare
                    // marker and render its contents indented underneath.
                    lines.add(indent + "-");
                    append(item, indent + INDENT, lines);
                } else {
                    lines.add(indent + "- " + scalar(item));
                }
            }
            return;
        }
        // A bare scalar is only reachable at the top level; a projection is normally an object.
        lines.add(indent + scalar(node));
    }

    /**
     * Renders a scalar node as text.
     *
     * @param node scalar node
     * @return the node's text form, with an explicit JSON null rather than an empty string
     */
    private static String scalar(JsonNode node) {
        return node.isNull() ? "null" : node.asText();
    }
}
