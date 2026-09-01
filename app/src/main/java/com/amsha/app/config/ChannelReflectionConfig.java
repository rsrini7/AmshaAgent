package com.amsha.app.config;

import com.amsha.channel.api.AttachmentType;
import com.amsha.channel.api.ChannelMessage;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.MediaAttachment;
import com.amsha.channel.api.structured.AgentContribution;
import com.amsha.channel.api.structured.ConflictSummary;
import com.amsha.channel.api.structured.SynthesisResult;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(
        targets = {
            AgentContribution.class,
            ConflictSummary.class,
            SynthesisResult.class,
            ChannelUser.class,
            MediaAttachment.class,
            AttachmentType.class,
            ChannelMessage.TelegramMessage.class,
            ChannelMessage.DiscordMessage.class,
            ChannelMessage.RestApiMessage.class,
            ChannelMessage.CliMessage.class,
            ChannelMessage.WebSocketMessage.class,
            ChannelMessage.GenericChannelMessage.class
        })
public class ChannelReflectionConfig {}
