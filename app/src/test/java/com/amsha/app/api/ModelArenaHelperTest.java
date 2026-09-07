package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.HttpException;

import jakarta.ws.rs.core.Response;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.amsha.app.ui.dto.ArenaCompareRequest;
import com.amsha.core.api.dto.AmshaErrorResponse;

import org.junit.jupiter.api.Test;

class ModelArenaHelperTest {

    @Test
    void estimateTokensHandlesNullEmptyAndWhitespace() {
        assertEquals(0, ModelArenaHelper.estimateTokens(null));
        assertEquals(0, ModelArenaHelper.estimateTokens(""));
        assertEquals(0, ModelArenaHelper.estimateTokens("   \t\n "));
    }

    @Test
    void estimateTokensWeightsCjkAndComplexScriptsSafely() {
        // 1000 Chinese characters: each character is weighted at 2 tokens = 2000 tokens
        String cjk1000 = "\u4e2d".repeat(1000);
        assertEquals(2000, ModelArenaHelper.estimateTokens(cjk1000));

        // 1500 Chinese characters: 3000 tokens, which correctly exceeds the 2048 budget
        String cjk1500 = "\u4e2d".repeat(1500);
        int estimated = ModelArenaHelper.estimateTokens(cjk1500);
        assertEquals(3000, estimated);
        assertTrue(estimated > ModelArenaHelper.MAX_INPUT_TOKENS);
    }

    @Test
    void estimateTokensHandlesSurrogatePairEmojis() {
        // Grinning face 😀 is \uD83D\uDE00 (code point U+1F600)
        String emojis = "\uD83D\uDE00".repeat(50);
        assertEquals(100, ModelArenaHelper.estimateTokens(emojis));
    }

    @Test
    void truncateToTokenBudgetEnforcesStrictBoundAndPreservesCodePoints() {
        assertEquals("", ModelArenaHelper.truncateToTokenBudget(null, 100));
        assertEquals("", ModelArenaHelper.truncateToTokenBudget("hello", 0));
        assertEquals("", ModelArenaHelper.truncateToTokenBudget("hello", -5));

        String input = "\u4e2d".repeat(100);
        String truncated = ModelArenaHelper.truncateToTokenBudget(input, 10);
        // Budget is 10 tokens; each CJK character is 2 tokens, so at most 5 characters
        assertEquals(5, truncated.length());
        assertTrue(ModelArenaHelper.estimateTokens(truncated) <= 10);

        // Emoji truncation does not leave dangling high surrogate
        String emojiInput = "\uD83D\uDE00".repeat(20);
        String truncatedEmojis = ModelArenaHelper.truncateToTokenBudget(emojiInput, 5);
        // Budget 5: each emoji is 2 tokens, so 2 emojis (4 char units), 4 tokens <= 5
        assertEquals(4, truncatedEmojis.length());
        assertTrue(Character.isDefined(truncatedEmojis.codePointAt(0)));
        assertTrue(Character.isDefined(truncatedEmojis.codePointAt(2)));
    }

    @Test
    void categorizeTypedHttpErrors() {
        assertEquals(
                "PROVIDER_AUTHENTICATION_ERROR",
                ModelArenaHelper.categorizeError(new HttpException(401, "unauthorized")));
        assertEquals(
                "PROVIDER_AUTHENTICATION_ERROR", ModelArenaHelper.categorizeError(new HttpException(403, "forbidden")));
        assertEquals(
                "PROVIDER_RATE_LIMIT", ModelArenaHelper.categorizeError(new HttpException(429, "rate limit exceeded")));
        assertEquals(
                "PROVIDER_UNKNOWN_MODEL", ModelArenaHelper.categorizeError(new HttpException(404, "model not found")));
        assertEquals("PROVIDER_TIMEOUT", ModelArenaHelper.categorizeError(new HttpException(408, "request timeout")));
        assertEquals("PROVIDER_TIMEOUT", ModelArenaHelper.categorizeError(new HttpException(504, "gateway timeout")));
    }

    @Test
    void categorizeTimeoutExceptions() {
        assertEquals(
                "PROVIDER_TIMEOUT", ModelArenaHelper.categorizeError(new HttpTimeoutException("request timed out")));
        assertEquals("PROVIDER_TIMEOUT", ModelArenaHelper.categorizeError(new TimeoutException("call timed out")));
    }

    @Test
    void categorizeTraversesNestedCauseChain() {
        Throwable nested = new RuntimeException("Outer wrapper", new HttpException(429, "Too many requests"));
        assertEquals("PROVIDER_RATE_LIMIT", ModelArenaHelper.categorizeError(nested));

        Throwable nestedTimeout = new IllegalStateException(new HttpTimeoutException("stream stall"));
        assertEquals("PROVIDER_TIMEOUT", ModelArenaHelper.categorizeError(nestedTimeout));
    }

    @Test
    void validateCompareRequestRejectsCjkPromptExceedingTokenBudget() {
        // 1500 CJK characters = 3000 tokens > 2048 max input tokens
        String cjkPrompt = "\u4e2d\u6587\u6d4b\u8bd5".repeat(375);
        ArenaCompareRequest request = new ArenaCompareRequest(cjkPrompt, List.of("omlx"), 50);

        Response response = ModelArenaHelper.validateCompareRequest(request);
        assertNotNull(response);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        AmshaErrorResponse entity = (AmshaErrorResponse) response.getEntity();
        assertTrue(entity.error().contains("Prompt exceeds maximum input budget of 2048 tokens"));
    }
}
