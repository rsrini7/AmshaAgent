package com.arcclaw.channel.telegram.config;

import com.arcclaw.channel.telegram.TelegramAgentHandler;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers Telegram-specific classes for reflection in native mode.
 */
@RegisterForReflection(
    targets = {
        TelegramAgentHandler.class
    }
)
public class TelegramReflectionConfig {
}
