package com.amsha.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

import com.amsha.common.util.HtmlArtifactUtil;
import com.amsha.tool.api.ToolCapability;

/**
 * Collapses generated HTML artifacts from shell tools into a short browser-preview notice.
 */
@ApplicationScoped
public class HtmlArtifactTransformer implements ObservationTransformer {

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public String transform(String toolName, String input) {
        if (input == null) {
            return "";
        }

        if (!ToolCapability.HOST_EXEC.toolName().equalsIgnoreCase(toolName)
                && !ToolCapability.SANDBOX_EXEC.toolName().equalsIgnoreCase(toolName)) {
            return input;
        }

        return HtmlArtifactUtil.collapseHtmlArtifact(input);
    }
}
