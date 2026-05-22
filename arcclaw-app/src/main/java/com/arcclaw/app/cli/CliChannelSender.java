package com.arcclaw.app.cli;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.arcclaw.channel.api.ChannelSender;
import com.arcclaw.channel.api.ChannelType;
import com.arcclaw.channel.api.ChannelUser;
import com.arcclaw.channel.api.ReplyNode;

/**
 * CLI implementation of {@link ChannelSender}.
 * Writes messages to stderr to avoid interfering with the interactive prompt.
 */
@ApplicationScoped
public class CliChannelSender implements ChannelSender {

    @Inject
    CliReplyRenderer renderer;

    @Override
    public ChannelType supports() {
        return ChannelType.CLI;
    }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        String text = renderer.render(nodes);
        sendRaw(recipient, text);
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        if (text == null || text.isBlank()) return;
        System.err.println(text);
        System.err.flush();
    }
}
