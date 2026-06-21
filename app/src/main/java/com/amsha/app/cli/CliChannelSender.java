package com.amsha.app.cli;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

import com.amsha.channel.api.ChannelSender;
import com.amsha.channel.api.ChannelType;
import com.amsha.channel.api.ChannelUser;
import com.amsha.channel.api.ReplyNode;

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
