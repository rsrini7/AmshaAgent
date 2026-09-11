package com.amsha.channel.telegram.config;

import com.amsha.channel.telegram.TelegramAgentHandler;
import com.amsha.channel.telegram.TelegramAllowlistGuard;
import com.amsha.channel.telegram.TelegramCallbackHandler;
import com.amsha.channel.telegram.TelegramChannelDaemon;
import com.amsha.channel.telegram.TelegramChannelPlugin;
import com.amsha.channel.telegram.TelegramChannelRoute;
import com.amsha.channel.telegram.TelegramChannelSender;
import com.amsha.channel.telegram.TelegramJobNotifier;
import com.amsha.channel.telegram.TelegramMarkdownV2Renderer;
import com.amsha.channel.telegram.TelegramMediaResolver;
import com.amsha.channel.telegram.TelegramMessageMapper;
import com.amsha.channel.telegram.TelegramStaleApprovalObserver;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers Telegram-specific classes for reflection in native mode.
 */
@RegisterForReflection(
        targets = {
            TelegramAgentHandler.class,
            TelegramCallbackHandler.class,
            TelegramMediaResolver.class,
            TelegramMessageMapper.class,
            TelegramChannelSender.class,
            TelegramMarkdownV2Renderer.class,
            TelegramChannelPlugin.class,
            TelegramChannelDaemon.class,
            TelegramChannelRoute.class,
            TelegramAllowlistGuard.class,
            TelegramJobNotifier.class,
            TelegramStaleApprovalObserver.class
        },
        classNames = {
            "com.amsha.channel.telegram.BatchHitlKeyboardBuilder",
            "org.apache.camel.component.telegram.model.Chat",
            "org.apache.camel.component.telegram.model.EditMessageTextMessage",
            "org.apache.camel.component.telegram.model.IncomingAudio",
            "org.apache.camel.component.telegram.model.IncomingCallbackQuery",
            "org.apache.camel.component.telegram.model.IncomingDocument",
            "org.apache.camel.component.telegram.model.IncomingMessage",
            "org.apache.camel.component.telegram.model.IncomingPhotoSize",
            "org.apache.camel.component.telegram.model.IncomingVoice",
            "org.apache.camel.component.telegram.model.InlineKeyboardButton",
            "org.apache.camel.component.telegram.model.InlineKeyboardMarkup",
            "org.apache.camel.component.telegram.model.OutgoingTextMessage",
            "org.apache.camel.component.telegram.model.OutgoingAudioMessage",
            "org.apache.camel.component.telegram.model.SendChatActionMessage",
            "org.apache.camel.component.telegram.model.SendChatActionMessage$Action",
            "org.apache.camel.component.telegram.model.User"
        })
public class TelegramReflectionConfig {}
