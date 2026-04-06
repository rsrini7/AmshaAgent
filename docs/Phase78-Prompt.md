# PROMPT — PHASE 78: arcclaw-channel-web + UI REST Endpoints (Tier A)

Role: Senior Java Architect & Senior Java Developer
Task: Phase 78 — Two deliverables in one phase:
  (A) Create a new Maven module `arcclaw-channel-web` implementing the full 6-interface
      Channel SPI so the browser participates in the same MessagePipeline as Telegram.
  (B) Add three new REST resources to `arcclaw-app` that the Lit frontend (Phase 79) will
      consume: AgentStatusResource (SSE), HitlResource, and ChatStreamResource (SSE).
Zero changes to arcclaw-core. No static HTML frontend is produced in this phase —
the browser UI is delivered in Phase 79 as the `arcclaw-ui` Lit module.

---

## Architecture Context and Other References
Refer @Architecture_Invariants.md (§1.4 Channel abstraction, §4.5 Channel plugin contract)
Refer @CHANNEL_SPI.md  (the 6-interface contract and step-by-step guide)
Refer @arcclaw-channel-telegram/ (the Telegram module is the reference implementation)
Refer @arcclaw-channel-api/src/main/java/com/arcclaw/channel/api/ (all SPI interfaces)
Refer @arcclaw-app/src/main/java/com/arcclaw/app/session/SessionRegistry.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/pipeline/MessagePipeline.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/hitl/HitlService.java (if exists)
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AgentRoleEnum.java
Refer @arcclaw-core/src/main/java/com/arcclaw/core/agent/AutonomyLevel.java

---

## IMPLEMENTATION REQUIREMENTS

### Common Requirements
1. Every new public class must have at least one test.
2. No changes to `arcclaw-core`, `arcclaw-channel-api`, or any existing module's source.
   The ONLY permitted changes to existing files:
   a. Root `pom.xml` — add `arcclaw-channel-web` to `<modules>`.
   b. `ChannelType.java` — add `WEB` enum constant.
   c. `arcclaw-app/pom.xml` — add `arcclaw-channel-web` as a dependency.
   d. `application.yaml` — add `arcclaw.channel.web.*` config block (see below).
3. All existing tests must remain green.
4. Use `static final Pattern` for any new regex — never compiled inline.
5. NO static HTML file is included in `arcclaw-channel-web`. The Lit SPA is a separate
   module delivered in Phase 79 (`arcclaw-ui`). The Quarkus CORS config already points to
   `localhost:3000` — that is where the frontend will be served by Vite dev server.
6. WebSocket sessions are scoped to a single browser tab. Multiple tabs = multiple sessions.
7. Test commands: `./mvnw clean test -pl arcclaw-channel-web,arcclaw-app`

---

## Part A — arcclaw-channel-web Module

### Module Setup

**New module:** `arcclaw-channel-web`

`pom.xml` must declare:
- Parent: `com.arcclaw:arcclaw-parent`
- Dependencies: `arcclaw-channel-api`, `arcclaw-core`, Quarkus WebSockets Next
  (use `quarkus-websockets-next` from BOM — preferred over legacy `quarkus-websockets`),
  standard Quarkus CDI.
- Include `jandex-maven-plugin` (mandatory per Architecture Invariant §15).
- Include `src/main/resources/META-INF/beans.xml` with `bean-discovery-mode="annotated"`.
- Do NOT include Quarkus Qute — this module has no server-side templates.

**Package root:** `com.arcclaw.channel.web`

---

### Component B1 — ChannelType.WEB

**Modified file:** `arcclaw-channel-api/.../ChannelType.java`
Add: `WEB`
No other changes to this file.

---

### Component B2 — WebChannelDaemon

**File:** `com.arcclaw.channel.web.WebChannelDaemon`

```java
@ApplicationScoped
public class WebChannelDaemon implements ChannelDaemon {

    @ConfigProperty(name = "arcclaw.channel.web.enabled", defaultValue = "true")
    boolean enabled;

    @Inject WebSocketSessionStore sessionStore;

    @Override public ChannelType channelType() { return ChannelType.WEB; }
    @Override public boolean isConfigured() { return enabled; }

    @Override
    public void start() {
        // WebSocket endpoint is registered by Quarkus automatically via @WebSocket.
        // No-op beyond logging.
        log.infof("Web channel daemon started — WebSocket endpoint /ws/chat active.");
    }

    @Override
    public void stop() {
        int count = sessionStore.all().size();
        sessionStore.closeAll();
        log.infof("Web channel daemon stopped — closed %d WebSocket sessions.", count);
    }
}
```

---

### Component B3 — WebSocketSessionStore

**File:** `com.arcclaw.channel.web.WebSocketSessionStore`

```java
@ApplicationScoped
public class WebSocketSessionStore {
    // ConcurrentHashMap<String sessionId, WebSocketConnection>
    // Use Quarkus WebSockets Next WebSocketConnection type.
    // Thread-safe — concurrent connects/disconnects are expected.

    public void register(String sessionId, WebSocketConnection conn);
    public void unregister(String sessionId);
    public Optional<WebSocketConnection> get(String sessionId);
    public Collection<WebSocketConnection> all();
    public void closeAll();  // called by WebChannelDaemon.stop()
}
```

`sessionId` is assigned on WebSocket open and stored in the connection's user data
under key `"arcclawSessionId"`.

---

### Component B4 — WebChatEndpoint (WebSocket server)

**File:** `com.arcclaw.channel.web.WebChatEndpoint`

Use Quarkus WebSockets Next `@WebSocket(path = "/ws/chat")`.

```java
@WebSocket(path = "/ws/chat")
public class WebChatEndpoint {

    @Inject WebSocketSessionStore sessionStore;
    @Inject MessagePipeline messagePipeline;
    @Inject WorkspaceManager workspaceManager;

    @OnOpen
    public void onOpen(WebSocketConnection conn) {
        String sessionId = UUID.randomUUID().toString();
        conn.userData().put("arcclawSessionId", sessionId);
        sessionStore.register(sessionId, conn);
        // Send handshake: {"type":"connected","sessionId":"<id>"}
        conn.sendText(buildConnectedJson(sessionId));
    }

    @OnTextMessage
    public void onMessage(String message, WebSocketConnection conn) {
        // 1. Parse JSON: {"type":"message","text":"<user text>"}
        // 2. If type != "message" → ignore silently.
        // 3. Extract sessionId from conn.userData().
        // 4. Build ChannelMessage using CliMessage(ChannelType.WEB, ...) as interim.
        //    // TODO: add WebMessage sealed type in arcclaw-channel-api
        // 5. Build ChannelUser with userId=sessionId, channelType=ChannelType.WEB.
        // 6. Build MessageContext.
        // 7. Run messagePipeline.handle(context) on a virtual thread:
        //    Thread.ofVirtual().start(() -> messagePipeline.handle(context));
    }

    @OnClose
    public void onClose(WebSocketConnection conn) {
        String sessionId = (String) conn.userData().get("arcclawSessionId");
        if (sessionId != null) {
            sessionStore.unregister(sessionId);
            workspaceManager.terminateSession(sessionId);
        }
    }

    @OnError
    public void onError(WebSocketConnection conn, Throwable t) {
        log.errorf(t, "WebSocket error for session %s", conn.userData().get("arcclawSessionId"));
        String sessionId = (String) conn.userData().get("arcclawSessionId");
        if (sessionId != null) sessionStore.unregister(sessionId);
    }
}
```

---

### Component B5 — WebChannelSender

**File:** `com.arcclaw.channel.web.WebChannelSender`

```java
@ApplicationScoped
public class WebChannelSender implements ChannelSender {

    @Inject WebSocketSessionStore sessionStore;
    @Inject WebReplyRenderer renderer;

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public void send(String platformChatId, List<ReplyNode> nodes) {
        String rendered = renderer.render(nodes);
        String payload = Json.createObjectBuilder()
            .add("type", "message")
            .add("role", "assistant")
            .add("text", rendered)
            .build().toString();

        sessionStore.get(platformChatId).ifPresentOrElse(
            conn -> {
                try {
                    conn.sendText(payload).await().atMost(Duration.ofSeconds(5));
                } catch (Exception e) {
                    log.warnf("Failed to send to session %s: %s", platformChatId, e.getMessage());
                    sessionStore.unregister(platformChatId);
                }
            },
            () -> log.warnf("WebChannelSender: no WS session for id=%s", platformChatId)
        );
    }
}
```

---

### Component B6 — WebReplyRenderer

**File:** `com.arcclaw.channel.web.WebReplyRenderer`

```java
@ApplicationScoped
public class WebReplyRenderer implements ReplyRenderer {

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public String render(List<ReplyNode> nodes) {
        // Walk the ReplyNode tree using the same pattern as TelegramMarkdownV2Renderer
        // but emit plain CommonMark markdown (no Telegram-specific escaping).
        // Text nodes → text as-is.
        // Code blocks → ```lang\n...\n```
        // Lists → standard markdown bullet/numbered list.
        // Return a single concatenated String.
        // Study TelegramMarkdownV2Renderer.java for the ReplyNode traversal pattern.
        // Do NOT copy the Telegram-specific escape logic.
    }
}
```

---

### Component B7 — WebAllowlist

**File:** `com.arcclaw.channel.web.WebAllowlist`

```java
@ApplicationScoped
public class WebAllowlist implements ChannelAllowlist {

    @ConfigProperty(name = "arcclaw.channel.web.require-auth", defaultValue = "false")
    boolean requireAuth;

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public boolean isAllowed(ChannelUser user) {
        // requireAuth=false (local dev default) → always true.
        // requireAuth=true → Phase 78 stub: return false for any user.
        // Full allowlist enforcement is Phase 80+.
        return !requireAuth;
    }
}
```

---

### Component B8 — WebJobNotifier

**File:** `com.arcclaw.channel.web.WebJobNotifier`

```java
@ApplicationScoped
public class WebJobNotifier implements ChannelJobNotifier {

    @Inject WebChannelSender sender;

    @Override public ChannelType channelType() { return ChannelType.WEB; }

    @Override
    public void notifyJobCompleted(String platformChatId, String renderedAnswer, String correlationId) {
        // Build a single TextNode with renderedAnswer.
        // Call sender.send(platformChatId, List.of(textNode)).
        // Lost notifications (closed tab) are acceptable for Phase 78 — sender logs a warn.
    }
}
```

---

### Component B9 — WebChannelPlugin

**File:** `com.arcclaw.channel.web.WebChannelPlugin`

```java
@ApplicationScoped
public class WebChannelPlugin implements ChannelPlugin {

    @Inject WebChannelSender sender;
    @Inject WebReplyRenderer renderer;
    @Inject WebAllowlist allowlist;
    @Inject WebJobNotifier jobNotifier;
    @Inject WebChannelDaemon daemon;

    @Override public ChannelType channelType() { return ChannelType.WEB; }
    @Override public ChannelSender sender()          { return sender; }
    @Override public ReplyRenderer renderer()        { return renderer; }
    @Override public ChannelAllowlist allowlist()    { return allowlist; }
    @Override public ChannelJobNotifier jobNotifier(){ return jobNotifier; }
    @Override public ChannelDaemon daemon()          { return daemon; }
}
```

---

### Configuration Block (application.yaml addition)

```yaml
arcclaw:
  channel:
    web:
      enabled: true           # Set false to disable web channel entirely
      require-auth: false     # Set true to enforce allowlist in production
  cors:
    origins: "http://localhost:3000"  # Lit dev server — already present, verify this exists
```

---

## Part B — New UI REST Resources in arcclaw-app

These three resources power the Lit frontend. They belong in `arcclaw-app`, not in
`arcclaw-channel-web`, because they expose read-only views of engine state — not channel
routing logic.

**Module:** `arcclaw-app`
**Package:** `com.arcclaw.app.ui`
**Base path:** `/api/v1` (must match existing API base path in `application.yaml`)

---

### Component B10 — AgentStatusDto and AgentEvent (DTOs)

**New files:**
- `com.arcclaw.app.ui.dto.AgentStatusDto`
- `com.arcclaw.app.ui.dto.AgentEvent`

```java
// AgentStatusDto — serialized as JSON, consumed by arc-agent-card Lit component
public record AgentStatusDto(
    String agentName,           // "Vishnu", "Saraswati", etc. — from AgentRoleEnum
    String role,                // "coordinator", "researcher", etc.
    String status,              // "idle" | "active" | "waiting_approval" | "error"
    String currentTask,         // nullable — current task description
    String autonomyLevel,       // "SUPERVISED" | "SEMI_AUTONOMOUS" | "AUTONOMOUS"
    long tokenCount             // accumulated token usage for this session
) {}

// AgentEvent — SSE payload pushed to arc-shell via /api/v1/agents/events
public record AgentEvent(
    String eventType,           // "agent-state" | "hitl-pending" | "checklist-update"
    String agentName,           // which agent changed state (nullable for hitl-pending)
    String payload              // JSON string with the event-specific data
) {}
```

---

### Component B11 — AgentStatusResource

**New file:** `com.arcclaw.app.ui.AgentStatusResource`

```java
@Path("agents")
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
public class AgentStatusResource {

    @Inject AgentStateView agentStateView;  // read-only view of current agent states
                                             // Inspect existing services — this may be
                                             // SessionRegistry, AgentLoop state, or a new
                                             // lightweight read model. Use whatever exposes
                                             // agent name + status in the codebase.

    @GET
    public List<AgentStatusDto> listAgents() {
        // Return current status of all configured agent roles.
        // If no session is active, all agents report status="idle".
        // Map AgentRoleEnum + AutonomyLevel to the DTO fields.
    }

    @GET
    @Path("events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<AgentEvent> agentEventStream() {
        // Return a Mutiny Multi that emits AgentEvent items.
        // Subscribe to CDI events: AgentStateChangedEvent, CheckListEvent, HitlPendingEvent.
        // Use Multi.createFrom().emitter(...) with a CDI observer that feeds the emitter.
        // The stream stays open until the client disconnects.
        // Emit a keepalive comment every 15 seconds to prevent proxy timeouts.
    }
}
```

**Implementation note:** If `AgentStateChangedEvent` does not yet exist as a CDI event type,
create `com.arcclaw.core.agent.event.AgentStateChangedEvent` record in `arcclaw-core`:
```java
public record AgentStateChangedEvent(String agentName, String newStatus, String currentTask) {}
```
Fire it from `AgentStateMachineRunner` at state transitions. This is the only permitted
addition to `arcclaw-core` in this phase — no logic changes.

---

### Component B12 — PendingApprovalDto and HitlResource

**New files:**
- `com.arcclaw.app.ui.dto.PendingApprovalDto`
- `com.arcclaw.app.ui.HitlResource`

```java
// PendingApprovalDto — serialized as JSON, consumed by arc-hitl-panel Lit component
public record PendingApprovalDto(
    String taskId,
    String toolName,
    Map<String, Object> params,
    Instant requestedAt,
    String agentName
) {}
```

```java
@Path("hitl")
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
public class HitlResource {

    @Inject HitlService hitlService;  // existing service in arcclaw-core

    @GET
    public List<PendingApprovalDto> listPending() {
        // Return all tasks currently suspended awaiting human approval.
        // Map HitlService.getPendingApprovals() to PendingApprovalDto.
    }

    @POST
    @Path("{taskId}/approve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response approve(@PathParam("taskId") String taskId) {
        // Call hitlService.approve(taskId).
        // Return 200 OK with {"status":"approved","taskId":"<id>"}.
        // Return 404 if taskId not found.
        // Return 409 if task already resolved.
    }

    @POST
    @Path("{taskId}/reject")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response reject(@PathParam("taskId") String taskId, String reason) {
        // Call hitlService.reject(taskId, reason).
        // Return 200 OK with {"status":"rejected","taskId":"<id>"}.
        // Return 404 if taskId not found.
        // reason may be blank — default to "Rejected by user" if blank.
    }
}
```

---

### Component B13 — ChatRequest and ChatStreamResource

**New files:**
- `com.arcclaw.app.ui.dto.ChatRequest`
- `com.arcclaw.app.ui.ChatStreamResource`

```java
// ChatRequest — JSON body for POST /api/v1/chat/stream
public record ChatRequest(
    String input,       // user message text — required, must not be blank
    String sessionId    // existing session ID, or null to create a new session
) {}
```

```java
@Path("chat")
@RunOnVirtualThread
public class ChatStreamResource {

    @Inject MessagePipeline messagePipeline;
    @Inject SessionRegistry sessionRegistry;

    @POST
    @Path("stream")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> streamChat(ChatRequest req) {
        // 1. Validate req.input() is not blank — emit SSE error event and complete if so.
        // 2. If req.sessionId() is null → generate a new UUID session ID.
        // 3. Build MessageContext with ChannelType.WEB and the session ID.
        // 4. Return a Multi<String> that:
        //    a. Feeds agent token output token-by-token as SSE data events.
        //    b. Emits a final SSE event {"type":"done","sessionId":"<id>"} when the
        //       agent completes its response.
        //    c. On error: emits SSE event {"type":"error","message":"<msg>"} and completes.
        // 5. The pipeline runs on the virtual thread provided by @RunOnVirtualThread.
        //    Use Multi.createFrom().emitter() and push tokens via the AgentOutputObserver.
        // NOTE: If the existing MessagePipeline does not support streaming token output,
        // run the full pipeline synchronously, then emit the complete response as a single
        // SSE data event. Add a // TODO: wire streaming token emitter comment.
    }
}
```

---

## Phase 78 Tests

**`WebReplyRendererTest`** (unit test, no CDI):
- Plain text node → exact text string.
- Code block node → fenced code block in output.
- Nested nodes → correct concatenation order.
- Empty node list → empty string.

**`WebAllowlistTest`** (unit test):
- `requireAuth=false` → `isAllowed()` returns true for any `ChannelUser`.
- `requireAuth=true` → `isAllowed()` returns false (Phase 78 stub).

**`WebSocketSessionStoreTest`** (unit test):
- Register + get → returns the registered connection.
- Register + unregister + get → `Optional.empty()`.
- `closeAll()` → calls `close()` on all registered connections (mock).
- Concurrent register/unregister from 10 threads → no `ConcurrentModificationException`.

**`WebChannelPluginDiscoveryTest`** (`@QuarkusTest`):
- Assert `ChannelPluginRegistry.find(ChannelType.WEB)` returns a non-empty `Optional`.
- Assert all 5 components are non-null via the plugin accessor methods.
- Model after `TelegramChannelRouteStartupTest.java`.

**`AgentStatusResourceTest`** (`@QuarkusTest`):
- `GET /api/v1/agents` → 200, JSON array with at least one agent (mock `AgentStateView`).
- Each element has non-null `agentName`, `role`, `status`, `autonomyLevel`.

**`HitlResourceTest`** (`@QuarkusTest`):
- `GET /api/v1/hitl` → 200, empty array when no pending approvals.
- `POST /api/v1/hitl/{id}/approve` with valid id → 200.
- `POST /api/v1/hitl/{id}/approve` with unknown id → 404.
- `POST /api/v1/hitl/{id}/reject` with blank reason → uses default message.

**`ChatStreamResourceTest`** (`@QuarkusTest`):
- `POST /api/v1/chat/stream` with blank input → SSE error event emitted.
- `POST /api/v1/chat/stream` with valid input → SSE stream opens, `done` event emitted.

---

## Phase 78 Delivery Checklist
- [ ] `arcclaw-channel-web` module created: `pom.xml`, `beans.xml`, jandex plugin
- [ ] `ChannelType.WEB` added
- [ ] All 6 Channel SPI interfaces implemented: Daemon, Sender, Renderer, Allowlist, JobNotifier, Plugin
- [ ] `WebChatEndpoint` Quarkus WebSockets Next handler routes through `MessagePipeline`
- [ ] `AgentStatusResource`, `HitlResource`, `ChatStreamResource` in `arcclaw-app`
- [ ] `AgentStateChangedEvent` CDI event record in `arcclaw-core` (additive only)
- [ ] All new tests green: `./mvnw clean test -pl arcclaw-channel-web,arcclaw-app`
- [ ] All existing tests still pass: `./mvnw clean test`
- [ ] NO static HTML/HTMX file added anywhere (frontend is Phase 79)
- [ ] CORS config in `application.yaml` allows `http://localhost:3000`
