package com.arcclaw.core.util;

import static org.junit.jupiter.api.Assertions.*;

import com.arcclaw.core.message.UserMessages;

import org.junit.jupiter.api.Test;

/**
 * Deep coverage tests for {@link AgentErrorMessageUtil}.
 * Tests all branches in sanitizeErrorMessage, sanitizeLlmErrorPhrases,
 * isRateLimitError, hashGoal, parseRetryAfterMillis, and humanReadableWait.
 */
class AgentErrorMessageUtilDeepTest {

    // -----------------------------------------------------------------------
    // safeMessage
    // -----------------------------------------------------------------------

    @Test
    void safeMessage_throwableWithMessage_returnsMessage() {
        assertEquals("boom", AgentErrorMessageUtil.safeMessage(new RuntimeException("boom")));
    }

    @Test
    void safeMessage_throwableNullMessage_returnsClassName() {
        assertEquals("NullPointerException", AgentErrorMessageUtil.safeMessage(new NullPointerException()));
    }

    @Test
    void safeMessage_nullThrowable_returnsUnknownError() {
        assertEquals("Unknown Error", AgentErrorMessageUtil.safeMessage(null));
    }

    // -----------------------------------------------------------------------
    // sanitizeErrorMessage — all branches
    // -----------------------------------------------------------------------

    @Test
    void sanitizeErrorMessage_null_returnsTaskFailed() {
        assertEquals(UserMessages.TASK_FAILED, AgentErrorMessageUtil.sanitizeErrorMessage(null));
    }

    @Test
    void sanitizeErrorMessage_jvmInternal_nullPointer() {
        String msg = "Cannot invoke \"String.length()\" because \"s\" is null";
        assertEquals(UserMessages.AI_UNEXPECTED_RESPONSE, AgentErrorMessageUtil.sanitizeErrorMessage(msg));
    }

    @Test
    void sanitizeErrorMessage_jvmInternal_forInputString() {
        assertEquals(
                UserMessages.AI_UNEXPECTED_RESPONSE,
                AgentErrorMessageUtil.sanitizeErrorMessage("For input string: \"abc\""));
    }

    @Test
    void sanitizeErrorMessage_jvmInternal_numberFormatException() {
        assertEquals(
                UserMessages.AI_UNEXPECTED_RESPONSE,
                AgentErrorMessageUtil.sanitizeErrorMessage("NumberFormatException: For input string"));
    }

    @Test
    void sanitizeErrorMessage_jvmInternal_nullLiteral() {
        assertEquals(
                UserMessages.AI_UNEXPECTED_RESPONSE, AgentErrorMessageUtil.sanitizeErrorMessage("null something else"));
    }

    @Test
    void sanitizeErrorMessage_stripsReasoningEnginePrefix() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("ReasoningEngine error: something broke");
        assertEquals("something broke", result);
    }

    @Test
    void sanitizeErrorMessage_stripsRuntimeExceptionPrefix() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("java.lang.RuntimeException: disk full");
        assertEquals("disk full", result);
    }

    @Test
    void sanitizeErrorMessage_stripsHttpExceptionPrefix() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(
                "dev.langchain4j.exception.HttpException: 429 Too Many Requests");
        assertEquals("429 Too Many Requests", result);
    }

    @Test
    void sanitizeErrorMessage_stripsToolExecutionFailedPrefix() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("TOOL_EXECUTION_FAILED: tool crash");
        assertEquals("tool crash", result);
    }

    @Test
    void sanitizeErrorMessage_modelNotFound_backtick() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("The model `gpt-5-turbo` does not exist");
        assertTrue(result.contains("gpt-5-turbo"), "Should extract model name");
        assertTrue(result.contains("Model Not Found"), "Should use modelNotFound format");
    }

    @Test
    void sanitizeErrorMessage_modelNotFound_quotes() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("model_not_found: model 'llama-999' not available");
        assertTrue(result.contains("llama-999"), "Should extract model name");
    }

    @Test
    void sanitizeErrorMessage_tpdWithTokenDetails() {
        String msg = "You have reached your tokens per day limit. Limit 500000, Used 499000, Requested 5000. "
                + "Please try again in 2h30m15s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("500000"), "Should include limit");
        assertTrue(result.contains("499000"), "Should include used");
        assertTrue(result.contains("5000"), "Should include requested");
        assertTrue(result.contains("2 hours"), "Should include human-readable wait");
    }

    @Test
    void sanitizeErrorMessage_tpdSimple() {
        String msg = "tokens per day limit exceeded. Please try again in 30m.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("30 minutes"), "Should include wait time");
    }

    @Test
    void sanitizeErrorMessage_rateLimit() {
        String msg = "Rate limit exceeded. Please try again in 60s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("1 minute"), "Should include human-readable wait");
    }

    @Test
    void sanitizeErrorMessage_rateLimitLowercase() {
        String msg = "rate limit hit. Please try again in 5.5s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("6 seconds"), "Should round 5.5s to 6 seconds");
    }

    @Test
    void sanitizeErrorMessage_throttled() {
        String msg = "Request throttled. Please try again in 10s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("10 seconds"));
    }

    @Test
    void sanitizeErrorMessage_overCapacity() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("Service is over capacity right now");
        assertEquals(UserMessages.OVER_CAPACITY, result);
    }

    @Test
    void sanitizeErrorMessage_toolValidation() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("tool call validation failed: bad schema");
        assertEquals(UserMessages.TOOL_VALIDATION_ERROR, result);
    }

    @Test
    void sanitizeErrorMessage_schemaError() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("SCHEMA_ERROR: invalid JSON");
        assertEquals(UserMessages.TOOL_VALIDATION_ERROR, result);
    }

    @Test
    void sanitizeErrorMessage_budgetExceeded() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("Budget Exceeded: $10 limit");
        assertEquals(UserMessages.BUDGET_EXCEEDED, result);
    }

    @Test
    void sanitizeErrorMessage_jsonMessageField() {
        String json = "{\"message\": \"Rate limit exceeded\", \"code\": 429}";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(json);
        // The "Rate limit exceeded" should be extracted and matched as rate limit
        assertTrue(
                result.contains("a few moments") || result.contains("Rate limit") || result.contains("rate limit"),
                "Should process JSON message field");
    }

    @Test
    void sanitizeErrorMessage_jsonDetailField() {
        String json = "{\"detail\": \"over capacity\"}";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(json);
        assertEquals(UserMessages.OVER_CAPACITY, result);
    }

    @Test
    void sanitizeErrorMessage_jsonWithEscapedQuotes() {
        String json = "{\"message\": \"model \\\"gpt-x\\\" does not exist\"}";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(json);
        assertTrue(result.contains("gpt-x"), "Should unescape and extract model name");
    }

    @Test
    void sanitizeErrorMessage_errorObjectFallback() {
        String msg = "Some wrapper {\"error\": \"internal\"} more text";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertEquals(UserMessages.AI_SERVICE_ERROR, result);
    }

    @Test
    void sanitizeErrorMessage_plainString() {
        String result = AgentErrorMessageUtil.sanitizeErrorMessage("Something went wrong");
        assertEquals("Something went wrong", result);
    }

    // -----------------------------------------------------------------------
    // sanitizeLlmErrorPhrases
    // -----------------------------------------------------------------------

    @Test
    void sanitizeLlmErrorPhrases_null_returnsNull() {
        assertNull(AgentErrorMessageUtil.sanitizeLlmErrorPhrases(null));
    }

    @Test
    void sanitizeLlmErrorPhrases_blank_returnsBlank() {
        assertEquals("", AgentErrorMessageUtil.sanitizeLlmErrorPhrases(""));
        assertEquals("   ", AgentErrorMessageUtil.sanitizeLlmErrorPhrases("   "));
    }

    @Test
    void sanitizeLlmErrorPhrases_shortMatch_returnsInternalError() {
        String answer = "The provided function definitions do not match the needs of the request.";
        assertEquals(UserMessages.INTERNAL_LLM_ERROR, AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer));
    }

    @Test
    void sanitizeLlmErrorPhrases_functionDefinitionsNoMatch() {
        String answer = "function definitions were provided but no function call was made by the model";
        assertEquals(UserMessages.INTERNAL_LLM_ERROR, AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer));
    }

    @Test
    void sanitizeLlmErrorPhrases_modelProducedInvalidResponse() {
        String answer = "The model produced an invalid response. Please retry.";
        assertEquals(UserMessages.INTERNAL_LLM_ERROR, AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer));
    }

    @Test
    void sanitizeLlmErrorPhrases_failedToCallTool() {
        String answer = "failed to call tool: timeout";
        assertEquals(UserMessages.INTERNAL_LLM_ERROR, AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer));
    }

    @Test
    void sanitizeLlmErrorPhrases_longStringWithMatch_returnsAsIs() {
        // Strings >= 500 chars are NOT sanitized even if they contain the pattern
        String longAnswer =
                "a".repeat(400) + " provided function definitions do not match the needs " + "b".repeat(100);
        assertTrue(longAnswer.length() >= 500);
        assertEquals(longAnswer, AgentErrorMessageUtil.sanitizeLlmErrorPhrases(longAnswer));
    }

    @Test
    void sanitizeLlmErrorPhrases_shortStringNoMatch_returnsAsIs() {
        String answer = "Here is the result of your computation: 42.";
        assertEquals(answer, AgentErrorMessageUtil.sanitizeLlmErrorPhrases(answer));
    }

    // -----------------------------------------------------------------------
    // isRateLimitError
    // -----------------------------------------------------------------------

    @Test
    void isRateLimitError_null_returnsFalse() {
        assertFalse(AgentErrorMessageUtil.isRateLimitError(null));
    }

    @Test
    void isRateLimitError_rateLimitLowercase() {
        assertTrue(AgentErrorMessageUtil.isRateLimitError("rate limit hit"));
    }

    @Test
    void isRateLimitError_rateLimitCapital() {
        assertTrue(AgentErrorMessageUtil.isRateLimitError("Rate limit exceeded"));
    }

    @Test
    void isRateLimitError_throttled() {
        assertTrue(AgentErrorMessageUtil.isRateLimitError("Request throttled"));
    }

    @Test
    void isRateLimitError_noMatch() {
        assertFalse(AgentErrorMessageUtil.isRateLimitError("Something else happened"));
    }

    // -----------------------------------------------------------------------
    // hashGoal
    // -----------------------------------------------------------------------

    @Test
    void hashGoal_null_returnsNone() {
        assertEquals("none", AgentErrorMessageUtil.hashGoal(null));
    }

    @Test
    void hashGoal_nonNull_returnsHashCode() {
        String goal = "build a calculator";
        assertEquals(String.valueOf(goal.hashCode()), AgentErrorMessageUtil.hashGoal(goal));
    }

    @Test
    void hashGoal_emptyString_returnsHash() {
        assertEquals(String.valueOf("".hashCode()), AgentErrorMessageUtil.hashGoal(""));
    }

    // -----------------------------------------------------------------------
    // parseRetryAfterMillis
    // -----------------------------------------------------------------------

    @Test
    void parseRetryAfterMillis_null_returnsZero() {
        assertEquals(0, AgentErrorMessageUtil.parseRetryAfterMillis(null));
    }

    @Test
    void parseRetryAfterMillis_noRetryHint_returnsZero() {
        assertEquals(0, AgentErrorMessageUtil.parseRetryAfterMillis(new RuntimeException("something")));
    }

    @Test
    void parseRetryAfterMillis_retryAfterHeader() {
        var ex = new RuntimeException("Error: retry-after: 30");
        assertEquals(30_000L, AgentErrorMessageUtil.parseRetryAfterMillis(ex));
    }

    @Test
    void parseRetryAfterMillis_retryAfterHeader_caseInsensitive() {
        var ex = new RuntimeException("Error: Retry-After: 15");
        assertEquals(15_000L, AgentErrorMessageUtil.parseRetryAfterMillis(ex));
    }

    @Test
    void parseRetryAfterMillis_tryAgainInSeconds() {
        var ex = new RuntimeException("Please try again in 45.5s");
        assertEquals(45_500L, AgentErrorMessageUtil.parseRetryAfterMillis(ex));
    }

    @Test
    void parseRetryAfterMillis_recursiveCause() {
        var inner = new RuntimeException("retry-after: 10");
        var outer = new RuntimeException("outer", inner);
        assertEquals(10_000L, AgentErrorMessageUtil.parseRetryAfterMillis(outer));
    }

    @Test
    void parseRetryAfterMillis_tryAgainNoS_returnsZero() {
        // "Please try again in 30" without "s" suffix - only RETRY_AFTER_HEADER_PATTERN matches
        var ex = new RuntimeException("Please try again in 30");
        // The RETRY_AFTER_PATTERN needs the "s" suffix, so it won't match
        // Falls through to cause (null) -> 0
        assertEquals(0, AgentErrorMessageUtil.parseRetryAfterMillis(ex));
    }

    // -----------------------------------------------------------------------
    // humanReadableWait (via sanitizeErrorMessage to exercise the path)
    // -----------------------------------------------------------------------

    @Test
    void humanReadableWait_hoursMinutesSeconds() {
        String msg = "Rate limit. Please try again in 1h2m3s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("1 hour"), "Should have singular hour");
        assertTrue(result.contains("2 minutes"), "Should have plural minutes");
        assertTrue(result.contains("3 seconds"), "Should have plural seconds");
    }

    @Test
    void humanReadableWait_secondsOnly() {
        String msg = "Rate limit. Please try again in 45s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("45 seconds"));
    }

    @Test
    void humanReadableWait_oneSecond() {
        String msg = "Rate limit. Please try again in 1s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("1 second"), "Should use singular 'second'");
    }

    @Test
    void humanReadableWait_minutesOnly() {
        String msg = "Rate limit. Please try again in 5m.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("5 minutes"));
    }

    @Test
    void humanReadableWait_noRetryHint_returnsFallback() {
        String msg = "Rate limit exceeded. Try later.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("a few moments"), "Should use fallback wait time");
    }

    @Test
    void humanReadableWait_trailingDot() {
        String msg = "Rate limit. Please try again in 10s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("10 seconds"));
    }

    @Test
    void humanReadableWait_secondsOverflowIntoMinutes() {
        String msg = "Rate limit. Please try again in 90s.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("1 minute"), "90s should be 1 minute 30 seconds");
        assertTrue(result.contains("30 seconds"));
    }

    @Test
    void humanReadableWait_minutesOverflowIntoHours() {
        String msg = "Rate limit. Please try again in 125m.";
        String result = AgentErrorMessageUtil.sanitizeErrorMessage(msg);
        assertTrue(result.contains("2 hours"), "125m should be 2 hours 5 minutes");
        assertTrue(result.contains("5 minutes"));
    }
}
