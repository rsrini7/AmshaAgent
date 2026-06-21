package com.amsha.channel.telegram.config;

import com.amsha.channel.telegram.TelegramAgentHandler;
import com.amsha.channel.telegram.TelegramCallbackHandler;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers Telegram-specific classes for reflection in native mode.
 */
@RegisterForReflection(targets = {TelegramAgentHandler.class, TelegramCallbackHandler.class})
public class TelegramReflectionConfig {}
