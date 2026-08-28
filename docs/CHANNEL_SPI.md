# Channel SPI Documentation

Adding a new channel (Discord, Slack, WhatsApp) to Amsha Agent requires zero changes to the core engine. You just need to create a new module that implements the 6-interface contract and registers it as an `@ApplicationScoped` CDI bean.

## The 6-Interface Contract

All interfaces live in `amsha-channel-api`. The core and app modules depend on these contracts, never on concrete implementations.

| Interface | Responsibility | Required |
|---|---|---|
| `ChannelPlugin` | Composite registration bean - wires all 5 below | Yes |
| `ChannelSender` | Sends `List<ReplyNode>` to the channel | Yes |
| `ReplyRenderer` | Converts `ReplyNode` tree to channel-native format | Yes |
| `ChannelAllowlist` | Determines if a `ChannelUser` is authorized | Yes |
| `ChannelJobNotifier` | Sends cron and approval timeout notifications | Yes |
| `ChannelDaemon` | Daemon lifecycle: `channelId()`, `isConfigured()`, `start()` | Yes |

## Multimodal Media

`channel-api` now carries optional multimodal payloads for inbound turns:

* `MediaAttachment` (`attachmentType: IMAGE|DOCUMENT_PDF|AUDIO|OTHER`, `sourceUri`/`inlineData`, `mimeType`, `fileName`, `sizeBytes`) attached to `ChannelMessage` via `List<MediaAttachment> attachments()`.
* Adapters (Telegram, WebSocket) populate `MediaAttachment` but **do not download** — download is deferred to `AbstractInboundMessagePipeline` which resolves `MediaDownloadPort` (`amsha-security`).
* Governed service: `GovernedMediaDownloadService` streams with `MediaBudgetLimits` (`10/25/50 MiB`), `MediaSecurityValidator` (SSRF IP blocklist + redirect revalidation), and `OutboundPurpose.MEDIA_DOWNLOAD` policy. Results are `DownloadedMedia` (`AutoCloseable` temp files). See [SECURITY.md#governed-media-download](./SECURITY.md#governed-media-download) and `amsha.media.*` in [CONFIGURATION.md](./CONFIGURATION.md#media-ingestion--download-limits).

## Supporting Types

### `ChannelType` (record)

Identifies the channel. Pre-defined constants: `TELEGRAM`, `DISCORD`, `SLACK`, `WEBSOCKET`, `REST_API`, `CLI`. For a new channel, add a constant or create inline: `new ChannelType("discord")`. The `id` is lowercased and used as the registry key.

### `ChannelUser` (record)

Immutable user identity: `(platformId, channelId, displayName, channel)`. The `platformId` is the platform-native user identifier (e.g. Telegram numeric ID as string). The `channelId` is the chat/channel identifier. Both must be non-null and non-blank.

### `MessageContext`

Wraps a `ChannelUser` + message text + session metadata. This is what gets dispatched to the `MessagePipeline` (see the message pipeline diagram in [ARCHITECTURE.md](ARCHITECTURE.md#message-pipeline-handler-chain) or directly view [amsha_message_pipeline.svg](assets/amsha_message_pipeline.svg)).

---

## Interface Method Signatures

### `ChannelPlugin`

```java
public interface ChannelPlugin {
    ChannelType channelType();      // Unique channel type identifier
    ChannelSender sender();         // Reply delivery
    ReplyRenderer renderer();       // ReplyNode → string conversion
    ChannelAllowlist allowlist();   // Authorization check
    ChannelJobNotifier jobNotifier(); // Cron/approval notifications
    ChannelDaemon daemon();         // Daemon lifecycle
    default boolean isConfigured()  // Delegates to daemon().isConfigured()
}
```

### `ChannelSender`

```java
public interface ChannelSender {
    ChannelType supports();
    void send(ChannelUser recipient, List<ReplyNode> nodes);  // Rendered delivery
    void sendRaw(ChannelUser recipient, String text);          // Pre-rendered plaintext
    default void sendBySession(String sessionId, String text)  // Session-based delivery (e.g. WebSocket tabs)
}
```

### `ReplyRenderer`

```java
public interface ReplyRenderer {
    ChannelType supports();
    
    // Renders a list of content nodes and interaction nodes (default implementation)
    default String render(List<ReplyNode> nodes) { ... }

    // Renders a list of content nodes (must be implemented by all channels)
    String renderContent(List<ContentNode> nodes);

    // Renders an interaction node (default implementation degrades gracefully)
    default String renderInteraction(InteractionNode node) {
        return node.fallbackText();
    }
}
```

### `ChannelAllowlist`

```java
public interface ChannelAllowlist {
    boolean isAllowed(String platformUserId, String username);
    boolean isSilentBlock();     // If true, blocked users get no response
    String getBlockResponse();   // Message sent to blocked users when not silent
}
```

### `ChannelJobNotifier`

```java
public interface ChannelJobNotifier {
    boolean supports(ChannelType channelType);
    void notifyJobCompleted(String recipient, String renderedMessage, String sessionId);
    default void notifyApprovalRequired(String recipient, String cronSessionId, String toolName, String renderedMessage);
    void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes);
}
```

### `ChannelDaemon`

```java
public interface ChannelDaemon {
    String channelId();           // Canonical lowercase ID (e.g. "telegram")
    boolean isConfigured();       // Returns false if required config is missing
    void start() throws Exception; // Starts the daemon (blocking until stopped)
}
```

---

## Inbound Message Handling (Daemon → Pipeline)

For channels that listen for messages (like Telegram), the `ChannelDaemon.start()` implementation is responsible for bridging external events to the `MessagePipeline`.

1. **Implement a Route/Listener**: Use Camel, JDA, WebSocket, or a raw listener to receive messages.
2. **Map to ChannelUser**: Construct a `ChannelUser` from the native platform message.
3. **Build MessageContext**: Create a `MessageContext` with the `ChannelUser`, message text, and any metadata.
4. **Dispatch through TurnDispatchService**: Inject `TurnDispatchService` and call
   `turnDispatchService.dispatchAsync(TurnRequest.mapped(message, message.correlationId()))`.
   Adapters MUST NOT inject `AgentLoop`, `ReasoningEngine`, `ReflexRegistry`,
   `SkillAwareIntentDetector`, or `MessagePipeline` directly. The `TurnDispatchService`
   dispatcher is the single transport-neutral entry point for all user-initiated turns
   — command parsing, intent classification, compaction, and agent dispatch all happen
   inside the shared pipeline without adapter involvement.

The `ChannelPluginRegistry` discovers all `ChannelPlugin` implementations via CDI `@Any Instance<ChannelPlugin>` at startup. Duplicate channel type registrations fail fast.

---

## Step-by-Step: Adding Discord as an Example

### Step 1: Create the Maven module

Create `channel-discord/` with a dependency on `amsha-channel-api`:

```xml
<dependency>
    <groupId>com.amsha</groupId>
    <artifactId>amsha-channel-api</artifactId>
</dependency>
```

Add the module to the root `pom.xml` `<modules>` list.

### Step 2: Add configuration properties

```properties
amsha.channel.discord.enabled=true
amsha.channel.discord.token=your-bot-token
amsha.channel.discord.allowed-user-ids=123456789,987654321
```

### Step 3: Implement `ChannelAllowlist`

```java
@ApplicationScoped
public class DiscordAllowlist implements ChannelAllowlist {

    @ConfigProperty(name = "amsha.channel.discord.allowed-user-ids")
    Optional<String> allowedUserIds;

    @Override
    public boolean isAllowed(String platformUserId, String username) {
        return allowedUserIds.map(ids -> ids.contains(platformUserId)).orElse(true);
    }

    @Override public boolean isSilentBlock() { return false; }
    @Override public String getBlockResponse() { return "Access denied."; }
}
```

### Step 4: Implement `ReplyRenderer`

```java
@ApplicationScoped
public class DiscordRenderer implements ReplyRenderer {

    @Override
    public ChannelType supports() { return ChannelType.DISCORD; }

    @Override
    public String renderContent(List<ContentNode> nodes) {
        return nodes.stream()
                .map(this::renderContentNode)
                .collect(Collectors.joining("\n\n"));
    }

    @Override
    public String renderInteraction(InteractionNode node) {
        if (node instanceof ApprovalPrompt ap) {
            return "⚠️ **APPROVAL REQUIRED**\n"
                    + "*Tool:* `" + ap.toolName() + "`\n"
                    + "*Session:* `" + ap.sessionId() + "`";
        }
        return node.fallbackText();
    }

    private String renderContentNode(ContentNode node) {
        return switch (node) {
            case ContentNode.Text t         -> t.content();
            case ContentNode.Heading h      -> "#".repeat(h.level()) + " " + h.text();
            case ContentNode.CodeBlock cb   -> "```" + cb.language() + "\n" + cb.code() + "\n```";
            case ContentNode.InlineCode ic  -> "`" + ic.code() + "`";
            case ContentNode.BulletList bl  -> bl.items().stream()
                    .map(item -> "* " + item)
                    .collect(Collectors.joining("\n"));
            case ContentNode.NumberedList nl -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nl.items().size(); i++) {
                    sb.append(i + 1).append(". ").append(nl.items().get(i));
                    if (i < nl.items().size() - 1) sb.append("\n");
                }
                yield sb.toString();
            }
            case ContentNode.KeyValue kv    -> "**" + kv.key() + ":** " + kv.value();
            case ContentNode.Separator _    -> "---";
            case ContentNode.ErrorMessage err -> "❌ **ERROR [" + err.category() + "]**: " + err.reason();
            default -> "[Unsupported content]";
        };
    }
}
```

### Step 5: Implement `ChannelSender`

```java
@ApplicationScoped
public class DiscordChannelSender implements ChannelSender {

    @Inject DiscordRenderer renderer;
    @Inject DiscordClient client;

    @Override
    public ChannelType supports() { return ChannelType.DISCORD; }

    @Override
    public void send(ChannelUser recipient, List<ReplyNode> nodes) {
        String text = renderer.render(nodes);
        client.sendMessage(recipient.channelId(), text);
    }

    @Override
    public void sendRaw(ChannelUser recipient, String text) {
        client.sendMessage(recipient.channelId(), text);
    }
}
```

### Step 6: Implement `ChannelJobNotifier`

```java
@ApplicationScoped
public class DiscordJobNotifier implements ChannelJobNotifier {

    @Override
    public boolean supports(ChannelType channelType) {
        return ChannelType.DISCORD.equals(channelType);
    }

    @Override
    public void notifyJobCompleted(String recipient, String renderedMessage, String sessionId) {
        // Send DM or channel message with job result
    }

    @Override
    public void notifyApprovalTimeout(String recipient, String sessionId, String pendingToolName, long timeoutMinutes) {
        // Notify user that approval window expired
    }
}
```

### Step 7: Implement `ChannelDaemon`

```java
@ApplicationScoped
public class DiscordDaemon implements ChannelDaemon {

    @ConfigProperty(name = "amsha.channel.discord.token")
    Optional<String> token;

    @Inject TurnDispatchService turnDispatchService;

    @Override
    public String channelId() { return "discord"; }

    @Override
    public boolean isConfigured() { return token.isPresent(); }

    @Override
    public void start() throws Exception {
        // Connect to Discord gateway, listen for messages
        // On message: turnDispatchService.dispatchAsync(
        //     TurnRequest.mapped(msg, msg.correlationId()));
    }
}
```

### Step 8: Implement `ChannelPlugin`

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

### Step 9: Register the module

Add to root `pom.xml`:
```xml
<module>channel-discord</module>
```

That's it. `ChannelPluginRegistry` will discover `DiscordChannelPlugin` via CDI at startup. No changes to `amsha-core` or `amsha-app` are required.

---

## Key Config Pattern

Gate the channel via configuration properties. In your `ChannelDaemon.isConfigured()`, return `false` if the token isn't present — this prevents the plugin from crashing Amsha Agent startup:

```properties
amsha.channel.discord.enabled=true
amsha.channel.discord.token=your-bot-token
```

## Reference Implementations

| Channel | Module | Key Classes |
|---|---|---|
| Telegram | `amsha-channel-telegram` | `TelegramChannelPlugin`, `TelegramChannelSender`, `TelegramMarkdownV2Renderer`, `TelegramAllowlistGuard`, `TelegramJobNotifier`, `TelegramChannelDaemon`, `TelegramChannelRoute`, `TelegramAgentHandler`, `TelegramCallbackHandler` |
| WebSocket | `amsha-channel-web` | `WebChannelPlugin`, `WebChatEndpoint`, `WebChannelSender`, `WebReplyRenderer`, `WebAllowlist`, `WebChannelDaemon` |

## Testing Your Plugin

At a minimum:
1. Write a CDI test that asserts all 5 components wire cleanly (use `ChannelPluginRegistryTest` as boilerplate).
2. Verify `isConfigured()` returns `false` when the token is missing.
3. Verify `ChannelAllowlist.isAllowed()` blocks unauthorized users.
4. Verify `ReplyRenderer.render()` produces valid output for your platform's markdown format.

---

## 📚 Related Documents

- [Architecture Guide](ARCHITECTURE.md) - Context on message processing & pipeline visual flow
- [Extension Guide](AMSHA_EXTENSION.md) - Overall guide to extending all parts of the agent
- [Message Pipeline Diagram](assets/amsha_message_pipeline.svg) - Visual representation of message processing

