# Channel SPI Documentation

Adding a new channel (Discord, Slack, WhatsApp) to ArcClaw requires zero changes to the core engine. You just need to create a new module (or package) that implements the 6-Interface Contract and register it as a ApplicationScoped CDI bean.

## The 6-Interface Contract

| Interface | Module | Responsibility | Required |
|---|---|---|---|
| `ChannelPlugin` | `arcclaw-app/channel` | Composite registration bean — wires all 5 below | Yes |
| `ChannelSender` | `arcclaw-channel-api` | Sends `List<ReplyNode>` to the channel | Yes |
| `ReplyRenderer` | `arcclaw-channel-api` | Converts `ReplyNode` tree to channel-native format | Yes |
| `ChannelAllowlist` | `arcclaw-channel-api` | Determines if a `ChannelUser` is authorized | Yes |
| `ChannelJobNotifier` | `arcclaw-app/channel` | Sends cron and approval timeout notifications | Yes |
| `ChannelDaemon` | `arcclaw-core/pipeline` | Daemon lifecycle: `isConfigured()`, `start()` | Yes |

## Step-by-Step: Adding Discord as an Example

1. **Step 1: Create Maven Module**
   Create `arcclaw-channel-discord` module with `pom.xml` depending on `arcclaw-channel-api`, `arcclaw-core`, and `arcclaw-app`. (Alternatively, if no separate module is needed, place inside `arcclaw-app`).
2. **Step 2: Implement ChannelSender**
   Implement `DiscordChannelSender implements ChannelSender` to send messages natively.
3. **Step 3: Implement ReplyRenderer**
   Implement `DiscordRenderer implements ReplyRenderer`. Render `ReplyNode.Heading` as Discord `**bold**`, `ReplyNode.CodeBlock` as ` ```lang ` fences, etc.
4. **Step 4: Implement ChannelAllowlist**
   Implement `DiscordAllowlist implements ChannelAllowlist` to gatekeep using `ARCCLAW_DISCORD_ALLOWED_GUILDS`.
5. **Step 5: Implement ChannelJobNotifier**
   Implement `DiscordJobNotifier implements ChannelJobNotifier` to support server notifications.
6. **Step 6: Implement ChannelDaemon**
   Implement `DiscordDaemon implements ChannelDaemon` connecting to your JDA bot lifecycle.
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

Check the reference Telegram implementation inside `arcclaw-app/src/main/java/com/arcclaw/app/channel/telegram/`:
- `TelegramChannelPlugin.java`
- `TelegramChannelSender.java`
- `TelegramMarkdownV2Renderer.java`
- `TelegramAllowlistGuard.java`
- `TelegramJobNotifier.java`
- `TelegramChannelDaemon.java`

## Testing Your Plugin

At a minimum, verify that your plugin is correctly discovered inside CDI tests, utilizing the `ChannelPluginRegistryTest` as a boilerplate to assert all 5 components start cleanly.
