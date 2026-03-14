package com.arcclaw.app.config;

import com.arcclaw.app.channel.telegram.TelegramAgentHandler;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection(targets = {
    TelegramAgentHandler.class
})
public class AppReflectionConfig {
}