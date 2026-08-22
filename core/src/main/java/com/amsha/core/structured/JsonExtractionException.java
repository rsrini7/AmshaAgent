package com.amsha.core.structured;

import com.amsha.common.exception.AmshaException;

/**
 * Thrown when JSON extraction fails despite permissive parsing.
 */
public class JsonExtractionException extends AmshaException {
    private final String rawResponse;
    private final Class<?> targetType;
    private final String jacksonPath;

    public JsonExtractionException(String message) {
        this(message, null, null, null, null);
    }

    public JsonExtractionException(
            String message, String rawResponse, Class<?> targetType, String jacksonPath, Throwable cause) {
        super(message, cause);
        this.rawResponse = rawResponse;
        this.targetType = targetType;
        this.jacksonPath = jacksonPath;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public Class<?> getTargetType() {
        return targetType;
    }

    public String getJacksonPath() {
        return jacksonPath;
    }
}
