package com.amsha.app.config;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

import com.amsha.common.util.SecretRedactor;

import org.jboss.logmanager.ExtLogRecord;

import io.quarkus.logging.LoggingFilter;

/** Sanitizes application and dependency log records before output. */
@LoggingFilter(name = SecretRedactingLogFilter.NAME)
public final class SecretRedactingLogFilter implements Filter {

    public static final String NAME = "secret-redaction";

    @Override
    public boolean isLoggable(LogRecord record) {
        if (record == null) return false;

        if (record instanceof ExtLogRecord extendedRecord) {
            record.setMessage(SecretRedactor.redact(extendedRecord.getFormattedMessage()));
            record.setParameters(null);
        } else {
            String message = record.getMessage();
            String redactedMessage = SecretRedactor.redact(message);
            if (!Objects.equals(message, redactedMessage)) {
                record.setMessage(redactedMessage);
            }
            redactParameters(record);
        }

        Throwable thrown = record.getThrown();
        if (thrown != null) {
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            record.setThrown(sanitizeThrowable(thrown, visited));
        }
        return true;
    }

    private static void redactParameters(LogRecord record) {
        Object[] parameters = record.getParameters();
        if (parameters == null) return;

        Object[] sanitized = parameters.clone();
        for (int i = 0; i < sanitized.length; i++) {
            Object parameter = sanitized[i];
            if (parameter == null) continue;

            if (parameter instanceof Throwable thrown) {
                Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                sanitized[i] = sanitizeThrowable(thrown, visited);
                continue;
            }

            String rendered = parameter.toString();
            String redacted = SecretRedactor.redact(rendered);
            if (!rendered.equals(redacted)) sanitized[i] = redacted;
        }
        record.setParameters(sanitized);
    }

    private static Throwable sanitizeThrowable(Throwable source, Set<Throwable> visited) {
        if (!visited.add(source)) return new SanitizedLogException("Cyclic exception cause");

        SanitizedLogException sanitized = new SanitizedLogException(SecretRedactor.redact(source.toString()));
        sanitized.setStackTrace(source.getStackTrace());

        Throwable cause = source.getCause();
        if (cause != null && cause != source) sanitized.initCause(sanitizeThrowable(cause, visited));
        for (Throwable suppressed : source.getSuppressed()) {
            sanitized.addSuppressed(sanitizeThrowable(suppressed, visited));
        }
        return sanitized;
    }

    private static final class SanitizedLogException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SanitizedLogException(String message) {
            super(message);
        }
    }
}
