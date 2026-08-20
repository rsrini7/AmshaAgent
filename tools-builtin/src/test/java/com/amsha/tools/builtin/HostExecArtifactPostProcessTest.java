package com.amsha.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.Map;

import com.amsha.core.tools.OutputTruncator;
import com.amsha.core.util.HtmlArtifactUtil;

import org.junit.jupiter.api.Test;

class HostExecArtifactPostProcessTest {

    @Test
    void templateRead_doesNotCollapseIntoGeneratedArtifactNotice() throws Exception {
        HostExecTool tool = new HostExecTool();
        setField(tool, "outputTruncator", new OutputTruncator(8192));

        String html = """
                <html>
                  <body><main>template</main></body>
                </html>
                """;

        String formatted = tool.postProcessOutput(html, Map.of("code", "cat /tmp/templates/viewer.html"));

        assertEquals(HtmlArtifactUtil.HTML_TEMPLATE_NOTICE, formatted);
    }

    @Test
    void generatedOutput_collapsesIntoArtifactNotice() throws Exception {
        HostExecTool tool = new HostExecTool();
        setField(tool, "outputTruncator", new OutputTruncator(8192));

        String html = """
                <html>
                  <body><main>artifact</main></body>
                </html>
                """;

        String formatted = tool.postProcessOutput(html, Map.of("code", "cat output/generated.html"));

        assertEquals(HtmlArtifactUtil.HTML_ARTIFACT_NOTICE, formatted);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
