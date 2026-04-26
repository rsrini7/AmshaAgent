# Channel SPI Documentation

Adding a new channel (Discord, Slack, WhatsApp) to ArcClaw requires zero changes to the core engine. You just need to create a new module (or package) that implements the 6-Interface Contract and register it as a ApplicationScoped CDI bean.

## The 6-Interface Contract

| Interface | Module | Responsibility | Required |
|---|---|---|---|
| `ChannelPlugin` | `arcclaw-app/channel` | Composite registration bean - wires all 5 below | Yes |
| `ChannelSender` | `arcclaw-channel-api` | Sends `List<ReplyNode>` to the channel | Yes |
| `ReplyRenderer` | `arcclaw-channel-api` | Converts `ReplyNode` tree to channel-native format | Yes |
| `ChannelAllowlist` | `arcclaw-channel-api` | Determines if a `ChannelUser` is authorized | Yes |
| `ChannelJobNotifier` | `arcclaw-channel-api` | Sends cron and approval timeout notifications | Yes |
| `ChannelDaemon` | `arcclaw-core/pipeline` | Daemon lifecycle: `isConfigured()`, `start()` | Yes |

## Inbound Message Handling (Daemon -> Pipeline)

For channels that listen for messages (like Telegram), the `ChannelDaemon.start()` implementation is responsible for bridging the external event to the `MessagePipeline`.

1.  **Implement a Route/Listener**: Use Camel, JDA, or a raw listener to receive messages.
2.  **Map to ChannelMessage**: Map the native platform message (e.g., `IncomingMessage`) to a `ChannelMessage` type.
3.  **Dispatch to Pipeline**: Use `@Inject MessagePipeline pipeline` to dispatch a `MessageContext`.

---

## Step-by-Step: Adding Discord as an Example

7. **Step 7: Implement ChannelPlugin**
   Create the composite registry CDI block.

```java
@ApplicationScoped
public class DiscordChannelPlugin implements ChannelPlugin {

    @Inject DiscordChannelSender sender;
    @Inject DiscordRenderer renderer;
    @Inject DiscordAllowlist allowlist;
    @Inject DiscordJobNotifier jobNotifier;
    @Inject DiscordDaemon daemon;

    @Override public ChannelType channelType() { return ChannelType.DISCORD; }
    @Override public ChannelSender sender() { return sender; }
    @Override public ReplyRenderer renderer() { return renderer; }
    @Override public ChannelAllowlist allowlist() { return allowlist; }
    @Override public ChannelJobNotifier jobNotifier() { return jobNotifier; }
    @Override public ChannelDaemon daemon() { return daemon; }
}
```

## Key Config Pattern

It's a best practice to gate the channel via configuration properties. Example:
```properties
arcclaw.channel.discord.token=your-bot-token
```
In your `DiscordDaemon`, implement `isConfigured()` returning `false` if the token isn't present to stop the plugin from crashing ArcClaw startup.

## Reference: Telegram Implementation

Check the reference Telegram implementation inside `arcclaw-channel-telegram/src/main/java/com/arcclaw/channel/telegram/`:
- `TelegramChannelPlugin.java`
- `TelegramChannelSender.java`
- `TelegramMarkdownV2Renderer.java`
- `TelegramAllowlistGuard.java`
- `TelegramJobNotifier.java`
- `TelegramChannelDaemon.java`

## Testing Your Plugin

At a minimum, verify that your plugin is correctly discovered inside CDI tests, utilizing the `ChannelPluginRegistryTest` as a boilerplate to assert all 5 components start cleanly.
