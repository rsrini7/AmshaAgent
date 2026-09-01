package com.amsha.core.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpeakableTextProjectorTest {

    @Test
    void doesNotSplitSurrogatePairAtCodePointBound() {
        // "abc" (3 cp) + emoji (1 cp, 2 chars) + 23 ascii letters = 27 code points
        String text = "abc🌍defghijklmnopqrstuvwxyz";
        String result = SpeakableTextProjector.truncate(text, 4);

        // 5 code points = "abc" + the whole emoji (2 chars), nothing past it
        assertEquals("abc🌍", result);
        assertTrue(result.contains("🌍"), "emoji must survive truncation intact");
        // no lone surrogate: code-point count equals char count for this result (emoji is 1 cp)
        assertEquals(
                result.length(),
                result.codePointCount(0, result.length()) + 1,
                "result must not contain a split surrogate pair");
    }

    @Test
    void emojiWithinBoundIsReturnedVerbatim() {
        String withEmoji = "hi 🚀 there";
        assertEquals(withEmoji, SpeakableTextProjector.truncate(withEmoji, 50));
        assertTrue(withEmoji.contains("🚀"));
    }

    @Test
    void multipleAstralSymbolsAtBoundaryStayIntact() {
        String text = "a😀b😎c🚀d🌍e"; // each emoji is 1 code point, 2 chars
        // 9 code points total (5 ascii + 4 emoji). Bound of 5 -> a😀b😎c (3 ascii + 2 emoji)
        String result = SpeakableTextProjector.truncate(text, 5);
        assertEquals("a😀b😎c", result);
        assertFalse(result.contains("🚀"));
        assertFalse(result.contains("🌍"));
    }

    @Test
    void prefersSentenceTerminatorNearBoundary() {
        String text = "First sentence. Second sentence that is quite long and should stop early.";
        // The trailing-half search window (last ~half) must include the first period, so keep the
        // bound small enough that the cut contains it inside the window.
        String result = SpeakableTextProjector.truncate(text, 20);
        assertEquals("First sentence.", result);
    }

    @Test
    void blankAndNonPositiveBoundReturnEmpty() {
        assertEquals("", SpeakableTextProjector.truncate(null, 10));
        assertEquals("", SpeakableTextProjector.truncate("   ", 10));
        assertEquals("", SpeakableTextProjector.truncate("hello", 0));
        assertEquals("", SpeakableTextProjector.truncate("hello", -5));
    }

    @Test
    void withinBoundReturnedVerbatim() {
        String text = "short answer";
        assertEquals(text, SpeakableTextProjector.truncate(text, 100));
    }
}
