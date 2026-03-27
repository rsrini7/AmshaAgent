## Minor Findings (Non-blocking for P73)

| # | Finding | Location | Severity |
|---|---|---|---|
| M1 | `pendingToolNames()` duplicated verbatim | `ApproveReflex` + `DenyReflex` | 🟢 |
| M2 | `autoApproveAllPending()` → N × DB roundtrips (load+save per tool) | Both reflexes | 🟢 |
| M3 | Missing test files: `BatchToolDecisionTest`, `ApproveReflexBatchTest`, `DenyReflexBatchTest`, `CheckpointResumeHandlerDecisionTest` | test modules | 🟡 |

M3 is worth adding now since the logic is complex and the test matrix was specified in the P72 prompt. M1 and M2 can be deferred to P73. 

=====
PHASE 73 — Telegram Inline Keyboard + Stale Checkpoint GC
ArcClaw P72 → P73
=====

Role: Senior Java Architect & Senior Java Developer

Goal: Two self-contained improvements:
  P73-A  Replace text-based per-tool approval commands with a Telegram
         inline keyboard (one row per tool: ✅ Approve / 🚫 Deny).
  P73-B  GC for stale AWAITING_APPROVAL checkpoints — sessions that
         never received a user response are auto-denied after a
         configurable TTL and the user is notified.

Architecture Context:
  Refer @Architecture_Invariants.md (mandatory).
  Add §7.24–§7.26 in Step 0 BEFORE writing any code.

Guiding Principles:
  1. All §7.1–§7.23 invariants remain in force.
  2. Zero new Maven dependencies. Telegram Bot API inline keyboard is
     already supported by the existing camel-telegram component via
     InlineKeyboardMarkup / InlineKeyboardButton objects from
     org.telegram.telegrambots.meta.api.objects.replykeyboard.
     VERIFY these classes are available before using them. If not,
     construct the keyboard as a raw JSON string via the Telegram
     sendMessage endpoint directly (fallback approach).
  3. Text commands (`approve github`, `deny manage_schedule`) MUST
     continue to work — inline keyboard is additive, not a replacement.
  4. Zero new DB columns. The stale-GC timer uses existing
     AgentCheckpoint.updatedAt + CheckpointStatus.AWAITING_APPROVAL.
  5. 877+ existing tests remain green.


=====
STEP 0 — Architecture_Invariants.md §7.24–§7.26
=====

  7.24 MUST: When a parallel batch HITL pause is sent over Telegram,
       the message MUST include an inline keyboard with one row per tool:
         [✅ github]  [🚫 github]
         [✅ manage_schedule]  [🚫 manage_schedule]
         [✅ Approve All]  [🚫 Deny All]
       Text-based commands remain supported for non-Telegram channels.

  7.25 MUST: Inline keyboard callback data MUST follow the format:
         BATCH_APPROVE:<sessionId>:<toolName>
         BATCH_DENY:<sessionId>:<toolName>
         BATCH_APPROVE_ALL:<sessionId>
         BATCH_DENY_ALL:<sessionId>
       The sessionId in the callback ensures the correct session is
       targeted even if the user has multiple active sessions.

  7.26 MUST: AWAITING_APPROVAL checkpoints older than
       arcclaw.hitl.timeout-hours (default: 24) MUST be auto-denied
       by the WorkspaceManager GC scheduler. The user MUST receive a
       Telegram notification that their approval window expired.


=====
PROMPT — PHASE 73-A: Telegram Inline Keyboard for Per-Tool HITL
=====

=====
Component 73-A1 — Verify Inline Keyboard API availability
=====

BEFORE writing any code, check if InlineKeyboardMarkup is on the classpath:

  grep -r "InlineKeyboardMarkup\|InlineKeyboardButton" \
      arcclaw-channel-telegram/pom.xml ../pom.xml

If found → use the telegrambots objects directly.
If NOT found → construct keyboard as raw JSON and pass it as a header
to the camel-telegram producerTemplate.sendBodyAndHeaders() call with
the Telegram sendMessage API format:
  {
    "inline_keyboard": [
      [{"text": "✅ github", "callback_data": "BATCH_APPROVE:sessionId:github"},
       {"text": "🚫 github", "callback_data": "BATCH_DENY:sessionId:github"}],
      ...
      [{"text": "✅ Approve All", "callback_data": "BATCH_APPROVE_ALL:sessionId"},
       {"text": "🚫 Deny All",    "callback_data": "BATCH_DENY_ALL:sessionId"}]
    ]
  }

USE the raw JSON approach if telegrambots InlineKeyboardMarkup
is not directly available via the camel-telegram dependency.

=====
Component 73-A2 — BatchHitlKeyboardBuilder (new)
=====

File: arcclaw-channel-telegram/.../BatchHitlKeyboardBuilder.java

NEW class — pure utility (no CDI), package-private:

  /**
   * Builds the Telegram inline_keyboard JSON for a parallel batch HITL pause.
   * §7.24 §7.25
   */
  class BatchHitlKeyboardBuilder {

      /**
       * @param sessionId  the active session ID (embedded in callback data)
       * @param toolNames  ordered list of tool names in the batch
       * @return           inline_keyboard JSON string for Telegram
       */
      static String buildKeyboardJson(String sessionId, List<String> toolNames) {
          // Build rows: one [✅ tool] [🚫 tool] row per tool
          // Final row: [✅ Approve All] [🚫 Deny All]
          // Escape sessionId and toolName in callback_data: replace `:` with `_`
          // if they contain `:` to avoid splitting issues in the handler.
          // ...
      }

      /**
       * Parses a callback_data string back into a BatchCallbackEvent.
       * Returns empty Optional if format is unrecognized.
       */
      static Optional<BatchCallbackEvent> parseCallback(String callbackData) { ... }

      sealed interface BatchCallbackEvent {
          record ApproveTool(String sessionId, String toolName)
              implements BatchCallbackEvent {}
          record DenyTool(String sessionId, String toolName)
              implements BatchCallbackEvent {}
          record ApproveAll(String sessionId) implements BatchCallbackEvent {}
          record DenyAll(String sessionId) implements BatchCallbackEvent {}
      }
  }

CALLBACK DATA FORMAT (§7.25):
  "BATCH_APPROVE:sessionId:toolName"   → ApproveTool
  "BATCH_DENY:sessionId:toolName"      → DenyTool
  "BATCH_APPROVE_ALL:sessionId"        → ApproveAll
  "BATCH_DENY_ALL:sessionId"           → DenyAll

CONSTRAINT: Telegram limits callback_data to 64 bytes.
  sessionId format: "chatId-timestamp" (typically ~20 chars)
  toolName max: typically 20-30 chars
  prefix: ~14 chars
  Total: ~54 chars — within limit for most cases.
  If callbackData > 64 bytes: truncate toolName to
  (64 - prefix.length - sessionId.length - 2) chars.
  Log a WARN if truncation occurs.

=====
Component 73-A3 — TelegramChannelSender sends keyboard for batch HITL
=====

File: arcclaw-channel-telegram/.../TelegramChannelSender.java

CURRENT: `sendRaw(String chatId, String text)` sends plain text.

ADD overload:
  /**
   * Sends a message with an optional inline keyboard.
   * §7.24
   */
  public void sendWithKeyboard(ChannelUser recipient, String text, String keyboardJson) {
      if (keyboardJson == null || keyboardJson.isBlank()) {
          sendRaw(recipient.channelId(), text);
          return;
      }
      // Use Telegram Bot API directly via producerTemplate
      // with reply_markup header for the inline keyboard
      // ... (raw JSON approach or telegrambots objects per 73-A1 decision)
  }

UPDATE TelegramAgentHandler.processPipeline() — after calling pipeline.handle():
  If the response text starts with `"⏸ Parallel batch"` (the formatHitlMessage
  batch prefix), extract tool names from the text and send with keyboard.

BETTER APPROACH: Add a new result type or use a marker in the AgentResult:
  When AgentResult.AwaitingApproval.toolName() starts with "parallel:",
  the Telegram channel should detect this and send the keyboard.

  In TelegramAgentHandler, after getting the result string:
    if (result instanceof AgentResult.AwaitingApproval aa
            && aa.toolName().startsWith("parallel:")) {
        String[] tools = aa.toolName().substring("parallel:".length()).split(",");
        String keyboardJson = BatchHitlKeyboardBuilder.buildKeyboardJson(
            sessionId, List.of(tools));
        sender.sendWithKeyboard(user, formattedResult, keyboardJson);
    } else {
        sender.send(user, formattedResult);
    }

NOTE: TelegramAgentHandler currently receives a String result, not an
AgentResult. Check whether AgentResult is accessible at the handler
level or if the keyboard JSON must be embedded in the message differently.
If AgentResult is not accessible, detect the batch format from the String:
  `formattedResult.contains("• \`")` → keyboard needed. Extract tool
  names from the bullet list lines.

=====
Component 73-A4 — TelegramCallbackHandler (new)
=====

File: arcclaw-channel-telegram/.../TelegramCallbackHandler.java

NEW class — handles Telegram callback_query updates (button taps).

  @ApplicationScoped
  public class TelegramCallbackHandler {

      @Inject ApproveReflex approveReflex;
      @Inject DenyReflex    denyReflex;
      @Inject TelegramChannelSender sender;

      /**
       * Called by TelegramAgentHandler when update.hasCallbackQuery().
       * Parses callback data, routes to ApproveReflex or DenyReflex,
       * and answers the callback to remove the loading spinner.
       */
      public void handle(Update update) {
          CallbackQuery cbq = update.getCallbackQuery();
          String callbackData = cbq.getData();
          String chatId = String.valueOf(cbq.getMessage().getChatId());

          Optional<BatchHitlKeyboardBuilder.BatchCallbackEvent> event =
              BatchHitlKeyboardBuilder.parseCallback(callbackData);

          if (event.isEmpty()) {
              log.warnf("[CALLBACK] Unrecognized callback_data: %s", callbackData);
              answerCallback(cbq.getId(), "Unknown action");
              return;
          }

          switch (event.get()) {
              case BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveTool(
                      String sessionId, String toolName) -> {
                  // Build a synthetic "/approve toolName" command and route
                  // through the existing ApproveReflex path
                  String input = "/approve " + toolName;
                  dispatchToReflex(approveReflex, input, chatId, sessionId, cbq);
              }
              case BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyTool(
                      String sessionId, String toolName) -> {
                  String input = "/deny " + toolName;
                  dispatchToReflex(denyReflex, input, chatId, sessionId, cbq);
              }
              case BatchHitlKeyboardBuilder.BatchCallbackEvent.ApproveAll(
                      String sessionId) -> {
                  dispatchToReflex(approveReflex, "/approve", chatId, sessionId, cbq);
              }
              case BatchHitlKeyboardBuilder.BatchCallbackEvent.DenyAll(
                      String sessionId) -> {
                  dispatchToReflex(denyReflex, "/deny", chatId, sessionId, cbq);
              }
          }
      }

      private void dispatchToReflex(AgentReflex reflex, String input,
                                     String chatId, String sessionId,
                                     CallbackQuery cbq) {
          // Build a MessageContext with the sessionId from the callback
          // Route through WorkspaceContext.run() with the correct session
          // Answer the callback query to dismiss spinner
          // Send the reflex response back to the chat
          answerCallback(cbq.getId(), "✅ Received");
          // ...
      }
  }

UPDATE TelegramAgentHandler to route callback_query updates:
  In the onUpdateReceived() / processUpdate() method:
    if (update.hasCallbackQuery()) {
        callbackHandler.handle(update);
        return;
    }

ALSO: After any per-tool decision (not all-decided yet), UPDATE the
existing keyboard message to reflect remaining pending tools by calling
Telegram editMessageReplyMarkup or editMessageText. This provides visual
feedback that the button tap was processed.

=====
Component 73-A5 — Update keyboard after each decision
=====

When a tool is approved/denied but not all decided yet, edit the original
HITL message to show which tools are still pending:

  In TelegramCallbackHandler.dispatchToReflex():
  After routing the reflex and getting the response string:
  - If response contains "Still waiting for" → not all decided yet:
    → Call editMessageText on the original messageId (from cbq.getMessage().getMessageId())
    → Update message to show remaining pending tools
    → Rebuild keyboard with only the remaining PENDING tools (approved/denied rows removed)
  - If response does NOT contain "Still waiting for" → all decided:
    → editMessageReplyMarkup with empty keyboard (remove buttons)
    → Send the agent result as a new message

ADD Telegram editMessageText call in TelegramChannelSender:
  public void editMessage(String chatId, int messageId,
                           String newText, String keyboardJson) {
      // Use Telegram Bot API editMessageText endpoint
  }


=====
PROMPT — PHASE 73-B: Stale AWAITING_APPROVAL Checkpoint GC
=====

=====
Component 73-B1 — StatefulAgentStore.findStaleAwaitingApproval()
=====

File: arcclaw-core/.../state/StatefulAgentStore.java  (interface + impl)

ADD to interface:
  /**
   * Returns all AWAITING_APPROVAL checkpoints whose updatedAt is
   * older than the given cutoff. Used by stale-checkpoint GC. §7.26
   */
  List<AgentCheckpoint> findStaleAwaitingApproval(Instant cutoff);

IMPLEMENTATION:
  JPQL:
    SELECT c FROM AgentCheckpoint c
    WHERE c.status = 'AWAITING_APPROVAL'
    AND c.updatedAt < :cutoff

  NOTE: updatedAt is a LocalDateTime in AgentCheckpoint. The cutoff
  Instant should be converted via:
    LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC)

=====
Component 73-B2 — WorkspaceManager.gcStaleApprovals()
=====

File: arcclaw-core/.../lifecycle/WorkspaceManager.java

ADD config property:
  @ConfigProperty(name = "arcclaw.hitl.timeout-hours", defaultValue = "24")
  long hitlTimeoutHours;

ADD CDI event for stale approval:
  @Inject Event<StaleApprovalExpired> staleApprovalEvent;

ADD new record in WorkspaceManager or a new event class:
  public record StaleApprovalExpired(
      String sessionId,
      String agentRole,
      String userId,
      String channelType,
      String channelPlatformId,
      String pendingToolName
  ) {}

ADD scheduled method (or call from existing GC @Scheduled method):
  /**
   * Expires AWAITING_APPROVAL checkpoints older than hitlTimeoutHours. §7.26
   * Called from the existing @Scheduled GC method, or as its own schedule.
   */
  void gcStaleApprovals() {
      Instant cutoff = Instant.now().minus(hitlTimeoutHours, ChronoUnit.HOURS);
      List<AgentCheckpoint> stale = agentStore.findStaleAwaitingApproval(cutoff);

      for (AgentCheckpoint cp : stale) {
          log.warnf("[HITL-GC] Session %s role %s has been awaiting approval for " +
                    ">%dh — auto-denying.", cp.getSessionId(), cp.getAgentRole(),
                    hitlTimeoutHours);

          // Auto-deny all pending tools if it's a batch
          if (cp.hasBatchToolDecisions()) {
              pendingToolNamesFrom(cp).forEach(toolName ->
                  agentStore.updateBatchToolDecision(
                      cp.getSessionId(), cp.getAgentRole(), toolName,
                      AgentSentinels.TOOL_DECISION_DENIED));
          }

          // Mark checkpoint as FAILED
          cp.setStatus(CheckpointStatus.FAILED);
          agentStore.save(cp);

          // Terminate the session
          terminateSession(cp.getSessionId());

          // Notify user via channel event
          staleApprovalEvent.fire(new StaleApprovalExpired(
              cp.getSessionId(), cp.getAgentRole(),
              /* userId from checkpoint */ cp.getSessionId(), // derive if available
              cp.getChannelType(),
              cp.getChannelPlatformId(),
              cp.getPendingToolName()
          ));
      }
  }

  private List<String> pendingToolNamesFrom(AgentCheckpoint cp) {
      // Same logic as ApproveReflex.pendingToolNames() —
      // extract into a shared utility (see also M1 from P72 review)
  }

NOTE: This method MUST NOT re-run the agent loop. It only marks the
checkpoint FAILED + terminates the session + fires a notification event.
The agent loop is NOT resumed for auto-denied stale checkpoints.

=====
Component 73-B3 — Telegram stale-approval notification
=====

File: arcclaw-channel-telegram/.../TelegramJobObserver.java
  (or a new TelegramStaleApprovalObserver.java)

ADD observer for StaleApprovalExpired event:

  @ApplicationScoped
  public class TelegramStaleApprovalObserver {

      @Inject TelegramChannelSender sender;

      void onStaleApproval(
              @Observes StaleApprovalExpired event) {

          if (!ChannelType.TELEGRAM.name().equalsIgnoreCase(event.channelType())) {
              return; // only handle Telegram channel
          }

          String toolDisplay = event.pendingToolName() != null
              && event.pendingToolName().startsWith("parallel:")
              ? "batch: " + event.pendingToolName().substring("parallel:".length())
              : event.pendingToolName();

          String msg = String.format(
              "⏱ Your approval window for `%s` has expired (>%dh).\n" +
              "The pending tool execution was automatically cancelled.\n" +
              "Start a new request to try again.",
              toolDisplay,
              /* hitlTimeoutHours */ 24);

          sender.sendRaw(event.channelPlatformId(), msg);
      }
  }

=====
Component 73-B4 — Extract pendingToolNamesFrom() to shared utility
=====

File: arcclaw-core/.../util/BatchDecisionUtil.java  (new)

This fixes M1 from the P72 review (duplicate pendingToolNames() in
ApproveReflex and DenyReflex) and provides a shared location for
WorkspaceManager to also use:

  /**
   * Utility for reading per-tool decision state from AgentCheckpoint.
   * Eliminates duplication across ApproveReflex, DenyReflex, WorkspaceManager.
   */
  public final class BatchDecisionUtil {

      private BatchDecisionUtil() {}

      /**
       * Returns tool names whose decision is still PENDING. §7.20
       */
      public static List<String> pendingToolNames(
              AgentCheckpoint cp, ObjectMapper objectMapper) {
          if (!cp.hasBatchToolDecisions()) return List.of();
          try {
              Map<String, String> map = objectMapper.readValue(
                  cp.getBatchToolDecisions(),
                  new TypeReference<Map<String, String>>() {});
              return map.entrySet().stream()
                  .filter(e -> AgentSentinels.TOOL_DECISION_PENDING.equals(e.getValue()))
                  .map(Map.Entry::getKey)
                  .toList();
          } catch (Exception e) {
              return List.of();
          }
      }

      /**
       * Returns all tool names regardless of decision status.
       */
      public static List<String> allToolNames(
              AgentCheckpoint cp, ObjectMapper objectMapper) {
          // same but no filter on value
      }
  }

UPDATE ApproveReflex, DenyReflex, and WorkspaceManager to use
BatchDecisionUtil.pendingToolNames() instead of their private copies.


=====
PHASE 73 EXECUTION ORDER
=====

  Step 0   §7.24–§7.26 in Architecture_Invariants.md          (no deps)

  73-B4    BatchDecisionUtil (extract shared utility)          (no deps)
           → Update ApproveReflex, DenyReflex to use it
           → Fixes P72 M1 (duplication)

  73-B1    StatefulAgentStore.findStaleAwaitingApproval()      (no deps)
  73-B2    WorkspaceManager.gcStaleApprovals()                 (needs B1, B4)
  73-B3    TelegramStaleApprovalObserver                       (needs B2)

  73-A1    Verify InlineKeyboardMarkup classpath availability  (no deps)
  73-A2    BatchHitlKeyboardBuilder                            (needs A1)
  73-A3    TelegramChannelSender.sendWithKeyboard()            (needs A2)
  73-A4    TelegramCallbackHandler                             (needs A2, A3)
  73-A5    Edit message after partial decisions                (needs A4)

  mvn clean verify (full build — all modules)
  Assert: 877+ tests green.


=====
KNOWN P73 LIMITATIONS (Tracked for P74+)
=====

  P74-1 — Partial-execute optimization:
           Pre-execute safe tools (no HITL) before pausing, store their
           results in the checkpoint, execute only the HITL tool on resume.
           Reduces latency for batches where only 1 of N needs approval.

  P74-2 — Approval delegation:
           Allow a different user (admin) to approve on behalf of another.
           Requires a new ApproveDelegation record and userId check bypass.

  P74-3 — Keyboard message update race condition:
           If two buttons are tapped simultaneously (unlikely but possible),
           two concurrent updateBatchToolDecision() calls may race.
           Both use REQUIRES_NEW transactions — the second write wins,
           which is correct. But isAllBatchToolsDecided() called after
           each may double-trigger resume. Add a CAS pattern or a
           RESUMING status to prevent double-resume.
