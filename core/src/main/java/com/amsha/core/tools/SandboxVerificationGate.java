package com.amsha.core.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

@ApplicationScoped
public class SandboxVerificationGate implements VerificationGate {

    private static final Logger log = Logger.getLogger(SandboxVerificationGate.class);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(\\w+)?\\n(.*?)\\n```", Pattern.DOTALL);

    @Inject
    SandboxExecTool sandboxExecTool;

    @Override
    public VerificationResult verify(ToolExecutionContext ctx, String output) {
        if (output == null || output.isBlank()) {
            return new VerificationResult(true, null);
        }

        Matcher matcher = CODE_BLOCK_PATTERN.matcher(output);

        while (matcher.find()) {
            String lang = matcher.group(1);
            String code = matcher.group(2);

            if (lang == null || lang.isBlank()) {
                continue;
            }
            lang = lang.toLowerCase(Locale.ROOT);

            // We only verify execution of active code blocks: python, java, go, node, bash
            if (!isExecutableLanguage(lang)) {
                continue;
            }

            log.infof(
                    "[VerificationGate] Intercepted executable code block (lang=%s). Running in sandbox verification gate...",
                    lang);
            try {
                ToolResponse response = sandboxExecTool.execute(
                        ctx,
                        Map.of(
                                "code", code,
                                "language", lang));

                if (!response.success()) {
                    log.warnf("[VerificationGate] Sandbox verification failed: %s", response.output());
                    return new VerificationResult(false, "Code block execution failed:\n" + response.output());
                }
                log.infof("[VerificationGate] Sandbox verification successful: %s", response.output());
            } catch (Exception e) {
                log.errorf("[VerificationGate] Sandbox verification threw exception: %s", e.getMessage());
                return new VerificationResult(false, "Code block execution threw exception:\n" + e.getMessage());
            }
        }

        return new VerificationResult(true, null);
    }

    private boolean isExecutableLanguage(String lang) {
        return "python".equals(lang)
                || "python3".equals(lang)
                || "java".equals(lang)
                || "go".equals(lang)
                || "node".equals(lang)
                || "bash".equals(lang);
    }
}
