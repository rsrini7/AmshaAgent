package com.amsha.channel.telegram;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;

import org.apache.camel.component.telegram.model.InlineKeyboardButton;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.junit.jupiter.api.Test;

class TelegramVoiceKeyboardBuilderTest {

    @Test
    void test_buildKeyboard_generates_paired_voice_buttons() {
        InlineKeyboardMarkup markup = TelegramVoiceKeyboardBuilder.buildKeyboard();
        assertNotNull(markup);

        List<List<InlineKeyboardButton>> rows = markup.getInlineKeyboard();
        assertNotNull(rows);
        assertFalse(rows.isEmpty());

        // Each full row must have exactly 2 buttons
        for (int i = 0; i < rows.size() - 1; i++) {
            assertEquals(2, rows.get(i).size());
        }

        // Verify callback data and formatting for all buttons
        boolean foundVivian = false;
        boolean foundSerena = false;
        boolean foundEric = false;
        boolean foundRyan = false;
        boolean foundAlloy = false;
        boolean foundOnyx = false;

        for (List<InlineKeyboardButton> row : rows) {
            for (InlineKeyboardButton button : row) {
                assertNotNull(button.getText());
                assertNotNull(button.getCallbackData());
                assertTrue(button.getCallbackData().startsWith("V|"));

                String voice = button.getCallbackData().substring(2);
                switch (voice.toLowerCase(Locale.ROOT)) {
                    case "vivian" -> {
                        assertEquals("👩 Vivian", button.getText());
                        foundVivian = true;
                    }
                    case "serena" -> {
                        assertEquals("👩 Serena", button.getText());
                        foundSerena = true;
                    }
                    case "eric" -> {
                        assertEquals("👨 Eric", button.getText());
                        foundEric = true;
                    }
                    case "ryan" -> {
                        assertEquals("👨 Ryan", button.getText());
                        foundRyan = true;
                    }
                    case "alloy" -> {
                        assertEquals("🎙️ Alloy", button.getText());
                        foundAlloy = true;
                    }
                    case "onyx" -> {
                        assertEquals("🎙️ Onyx", button.getText());
                        foundOnyx = true;
                    }
                    default -> assertTrue(button.getText().contains("Default"));
                }
            }
        }

        assertTrue(foundVivian);
        assertTrue(foundSerena);
        assertTrue(foundEric);
        assertTrue(foundRyan);
        assertTrue(foundAlloy);
        assertTrue(foundOnyx);
    }
}
