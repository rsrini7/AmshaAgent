package com.amsha.channel.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.amsha.channel.api.preference.UserPreferenceService;

import org.apache.camel.component.telegram.model.InlineKeyboardButton;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;

public final class TelegramVoiceKeyboardBuilder {

    private TelegramVoiceKeyboardBuilder() {}

    public static InlineKeyboardMarkup buildKeyboard() {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (String voice : UserPreferenceService.PREDEFINED_VOICES) {
            String label = formatVoiceLabel(voice);
            currentRow.add(InlineKeyboardButton.builder()
                    .text(label)
                    .callbackData("V|" + voice)
                    .build());
            if (currentRow.size() == 2) {
                rows.add(List.copyOf(currentRow));
                currentRow.clear();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(List.copyOf(currentRow));
        }

        return InlineKeyboardMarkup.builder().inlineKeyboard(rows).build();
    }

    private static String formatVoiceLabel(String voice) {
        return switch (voice.toLowerCase(Locale.ROOT)) {
            case "vivian" -> "👩 Vivian";
            case "serena" -> "👩 Serena";
            case "eric" -> "👨 Eric";
            case "ryan" -> "👨 Ryan";
            case "alloy" -> "🎙️ Alloy";
            case "onyx" -> "🎙️ Onyx";
            default -> "🎲 Default (System)";
        };
    }
}
