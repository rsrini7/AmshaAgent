package com.amsha.app.config;

import com.amsha.channel.api.AttachmentType;
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
            AttachmentType.class
        })
public class ChannelReflectionConfig {}
