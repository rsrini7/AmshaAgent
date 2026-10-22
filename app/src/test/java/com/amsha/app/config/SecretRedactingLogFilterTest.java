package com.amsha.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

class SecretRedactingLogFilterTest {

    private static final String TOKEN = "123456789:AAExampleTelegramCredential_123456789";

    private final SecretRedactingLogFilter filter = new SecretRedactingLogFilter();

    @Test
    void preservesPrintfFormattingWhileRedactingParameters() {
        ExtLogRecord record = new ExtLogRecord(
                Level.INFO,
                "Decision-evidence corpus v%d loaded: bot-token=%s",
                ExtLogRecord.FormatStyle.PRINTF,
                "test");
        record.setParameters(new Object[] {3, TOKEN});

        assertTrue(filter.isLoggable(record));

        assertEquals("Decision-evidence corpus v3 loaded: bot-token=[REDACTED]", record.getFormattedMessage());
        assertNull(record.getParameters());
    }

    @Test
    void redactsMessageParametersAndThrowableChain() {
        LogRecord record = new LogRecord(
                Level.WARNING, "Failed polling https://api.telegram.org/bot" + TOKEN + "/getUpdates parameter={0}");
        record.setParameters(new Object[] {"bot-token=" + TOKEN});
        RuntimeException cause = new RuntimeException("Authorization: Bearer " + TOKEN);
        RuntimeException thrown = new RuntimeException("request used " + TOKEN, cause);
        record.setThrown(thrown);

        assertTrue(filter.isLoggable(record));

        String rendered = render(record);
        assertFalse(rendered.contains(TOKEN));
        assertTrue(rendered.contains("[REDACTED]"));
        assertNotSame(thrown, record.getThrown());
    }

    private static String render(LogRecord record) {
        StringWriter output = new StringWriter();
        output.append(record.getMessage());
        for (Object parameter : record.getParameters()) output.append(' ').append(parameter.toString());
        record.getThrown().printStackTrace(new PrintWriter(output));
        return output.toString();
    }
}
