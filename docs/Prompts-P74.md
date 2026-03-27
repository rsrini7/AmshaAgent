```
═══════════════════════════════════════════════════════════════════════════
PHASE 74 — Keyboard Edit + MarkdownV2 + Double-Tap Race Guard
ArcClaw P73 → P74
═══════════════════════════════════════════════════════════════════════════

Role: Senior Java Architect & Senior Java Developer

Goal: Three focused improvements left over from P73:
  P74-A  Implement editMessage() properly so the HITL keyboard
         updates in-place after each per-tool decision instead of
         being silently stubbed.
  P74-B  MarkdownV2 rendering for sendWithKeyboard() messages —
         pipe the text through TelegramMarkdownV2Renderer.escape()
         before setting ParseMode.
  P74-C  Prevent double-resume race when two callback button taps
         arrive simultaneously for the same session (P73-P74-3 item).

Architecture Context:
  Refer @Architecture_Invariants.md (mandatory).
  Add §7.27–§7.29 in Step 0 BEFORE writing any code.

Guiding Principles:
  1. All §7.1–§7.26 invariants remain in force.
  2. Zero new Maven dependencies. All three sub-phases must be
     implementable with what is already on the classpath.
  3. 877+ existing tests remain green.


═══════════════════════════════════════════════════════════════════════════
STEP 0 — Architecture_Invariants.md §7.27–§7.29
═══════════════════════════════════════════════════════════════════════════

  7.27 MUST: After each per-tool decision (not all decided yet),
       the original HITL keyboard message MUST be edited in-place
       to show only the remaining PENDING tools. Decided tools
       (APPROVED/DENIED) MUST be removed from the keyboard rows.
       The "Approve All / Deny All" row is always retained until
       all tools are decided.

  7.28 MUST: All text sent via sendWithKeyboard() MUST be escaped
       for MarkdownV2 before ParseMode="MarkdownV2" is applied.
       The escaping MUST use TelegramMarkdownV2Renderer.escape()
       which already handles all 18 Telegram special characters.
       AgentResultFormatter.formatHitlMessage() MUST produce
       MarkdownV2-safe output (backtick code spans are valid in
       MarkdownV2 and must be preserved as-is).

  7.29 INVARIANT: A parallel batch checkpoint MUST transition to
       a RESUMING pseudo-status (stored as a transient flag, NOT
       a new DB column) atomically before resumeParallelBatch()
       is called. If the flag is already set, the second caller
       MUST return a no-op response. This prevents double-resume
       when two callback_query updates arrive concurrently.
       The flag lives in a ConcurrentHashMap in StatefulAgentStore
       and is cleared when the session terminates.


═══════════════════════════════════════════════════════════════════════════
PROMPT — PHASE 74-A: editMessage() Proper Implementation
═══════════════════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────────────────────
Component 74-A1 — Verify EditMessageReplyMarkup in camel-telegram
──────────────────────────────────────────────────────────────────────────

BEFORE writing code, grep the classpath:

  grep -r "EditMessageReplyMarkup\|OutgoingEditMessage" \
      ~/.m2/repository/org/apache/camel

If org.apache.camel.component.telegram.model.EditMessageReplyMarkup
exists → use it (Path A).
If NOT found → use the JDK HttpClient direct Bot API call (Path B).

PATH A — Camel model:

  public void editMessage(String chatId, int messageId,
                           String newText, InlineKeyboardMarkup markup) {
      if (markup == null) {
          // No keyboard to show — remove buttons entirely
          EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
          edit.setChatId(chatId);
          edit.setMessageId(messageId);
          edit.setReplyMarkup(new InlineKeyboardMarkup()); // empty = remove
          producerTemplate.sendBodyAndHeader(
              "telegram:bots", edit,
              TelegramConstants.TELEGRAM_CHAT_ID, chatId);
      } else {
          EditMessageReplyMarkup edit = new EditMessageReplyMarkup();
          edit.setChatId(chatId);
          edit.setMessageId(messageId);
          edit.setReplyMarkup(markup);
          producerTemplate.sendBodyAndHeader(
              "telegram:bots", edit,
              TelegramConstants.TELEGRAM_CHAT_ID, chatId);
      }
  }

PATH B — JDK HttpClient (same pattern as answerCallback()):

  public void editMessage(String chatId, int messageId,
                           String newText, InlineKeyboardMarkup markup) {
      try {
          String replyMarkupJson = markup != null
              ? objectMapper.writeValueAsString(markup)
              : "{\"inline_keyboard\":[]}";
          String body = String.format(
            "{\"chat_id\":\"%s\",\"message_id\":%d,\"text\":\"%s\",\"reply_markup\":%s}",
            chatId, messageId,
            newText != null ? newText : "",
            replyMarkupJson);
          java.net.http.HttpClient.newHttpClient().sendAsync(
              java.net.http.HttpRequest.newBuilder()
                  .uri(java.net.URI.create(
                      "https://api.telegram.org/bot" + botToken
                      + "/editMessageText"))
                  .header("Content-Type", "application/json")
                  .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.discarding());
          log.debugf("editMessage sent for chatId=%s msgId=%d", chatId, messageId);
      } catch (Exception e) {
          log.warnf("editMessage failed for chatId=%s msgId=%d: %s",
                    chatId, messageId, e.getMessage());
      }
  }

  REQUIRES: @Inject ObjectMapper objectMapper; and
            @ConfigProperty(name="arcclaw.channel.telegram.bot-token") String botToken;
            in TelegramChannelSender (inject alongside producerTemplate).

ADD to TelegramChannelSender:
  @Inject ObjectMapper objectMapper;
  @ConfigProperty(name = "arcclaw.channel.telegram.bot-token")
  String botToken;

REMOVE the P74 deferred comment stub — the stub is now replaced.

──────────────────────────────────────────────────────────────────────────
Component 74-A2 — TelegramCallbackHandler: remove keyboard on completion
──────────────────────────────────────────────────────────────────────────

In updateOrRemoveKeyboard(), the "remove keyboard" path currently calls:
  sender.editMessage(chatId, messageId, "Approval process completed.", null);

With editMessage() now implemented, this correctly removes the keyboard.
No code change needed here — the stub removal in 74-A1 is sufficient.

VERIFY the test for updateOrRemoveKeyboard() now exercises the real path.

──────────────────────────────────────────────────────────────────────────
Component 74-A3 — buildKeyboard() for remaining PENDING tools only
──────────────────────────────────────────────────────────────────────────

File: TelegramCallbackHandler.updateOrRemoveKeyboard()

The current call:
  BatchHitlKeyboardBuilder.buildKeyboard(sessionId, pending)

Already passes only the PENDING tool names (from BatchDecisionUtil). This
is correct per §7.27. Verify the "Approve All / Deny All" final row is
always included regardless — it is, since buildKeyboard() always appends
it. No code change needed.


═══════════════════════════════════════════════════════════════════════════
PROMPT — PHASE 74-B: MarkdownV2 for sendWithKeyboard() Text
═══════════════════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────────────────────
Component 74-B1 — Escape HITL text before sendWithKeyboard()
──────────────────────────────────────────────────────────────────────────

File: TelegramChannelSender.sendWithKeyboard()

CURRENT (P73 state):
  OutgoingTextMessage msg = new OutgoingTextMessage();
  msg.setText(text);
  // P74: add MarkdownV2 only after text goes through escape()
  msg.setReplyMarkup(markup);

REPLACE WITH:
  OutgoingTextMessage msg = new OutgoingTextMessage();
  // §7.28: escape for MarkdownV2 before applying parse mode.
  // TelegramMarkdownV2Renderer.escape() handles all 18 special chars
  // and preserves backtick code spans as-is.
  msg.setText(TelegramMarkdownV2Renderer.escape(text));
  msg.setParseMode("MarkdownV2");
  msg.setReplyMarkup(markup);

ADD import:
  // TelegramMarkdownV2Renderer is in the same package — no import needed.

──────────────────────────────────────────────────────────────────────────
Component 74-B2 — AgentResultFormatter.formatHitlMessage() MarkdownV2-safe
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../util/AgentResultFormatter.java

The current output contains:
  "**To approve/deny specific tools:**\n"
  " `/approve <toolName>`\n"

In MarkdownV2, backtick code spans are valid. Bold `**text**` is also
valid in MarkdownV2. However, `<` and `>` in `<toolName>` are special
characters in MarkdownV2 and MUST be escaped as `\<` and `\>`.

REPLACE:
  .append(" `/approve").append(cronPrefix).append(" <toolName>`\n")
  .append(" `/deny").append(cronPrefix).append(" <toolName>`\n\n")

WITH:
  .append(" `/approve").append(cronPrefix).append(" \\<toolName\\>`\n")
  .append(" `/deny").append(cronPrefix).append(" \\<toolName\\>`\n\n")

NOTE: The `\\<` here is a Java string literal for `\<` — the MarkdownV2
escape sequence. TelegramMarkdownV2Renderer.escape() should NOT be called
on this string at the formatter level because the formatter already
produces correct MarkdownV2 output. The escape() call happens in
sendWithKeyboard() (74-B1) which would double-escape if called again.

To prevent double-escaping: add a boolean flag or a marker wrapper.
SIMPLER APPROACH: split the concern clearly —
  - AgentResultFormatter.formatHitlMessage() → produces plain text,
    NO MarkdownV2 in the string, just newlines and backtick code spans.
  - sendWithKeyboard() calls escape() on the plain text → produces
    valid MarkdownV2.
  - REMOVE the `**bold**` markers from formatHitlMessage() since
    escape() would not interpret those as bold — it would escape the `*`.

REVISED formatHitlMessage() batch section (plain text, no MarkdownV2):

  sb.append("To approve/deny specific tools:\n");
  sb.append("  /approve").append(cronPrefix).append(" <toolName>\n");
  sb.append("  /deny").append(cronPrefix).append(" <toolName>\n\n");
  sb.append("Or decide all at once:\n");
  sb.append("  /approve").append(cronPrefix)
    .append(" or /deny").append(cronPrefix).append(" (approve/deny all)");

  After escape() in sendWithKeyboard():
  → "<", ">" → "\<", "\>"
  → "/" → "\/" (if needed — Telegram doesn't require escaping /)
  → backtick spans preserved

This produces clean, readable Telegram output with no formatting
artifacts and no accidental escaping.

ALSO UPDATE: the single-tool path in formatHitlMessage():
  REPLACE:
    sb.append("Reply `/approve`...`/deny`...");
  WITH (plain text):
    sb.append("Reply /approve").append(cronPrefix)
      .append(" or /deny").append(cronPrefix)
      .append(" (confirm/cancel)");


═══════════════════════════════════════════════════════════════════════════
PROMPT — PHASE 74-C: Double-Tap Race Guard (§7.29)
═══════════════════════════════════════════════════════════════════════════

──────────────────────────────────────────────────────────────────────────
Component 74-C1 — In-memory RESUMING flag in StatefulAgentStore
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../state/StatefulAgentStoreImpl.java

ADD a ConcurrentHashMap field:

  /**
   * In-memory set of sessionIds currently in the process of resuming
   * a parallel batch. Prevents double-resume from concurrent callbacks.
   * §7.29 — NOT persisted; cleared on session termination.
   */
  private final java.util.concurrent.ConcurrentHashMap<String, Boolean>
      resumingBatches = new java.util.concurrent.ConcurrentHashMap<>();

ADD to interface StatefulAgentStore:

  /**
   * Atomically marks the session as RESUMING.
   * @return true if this caller is the first to mark it (safe to resume)
   *         false if another thread already marked it (skip — already resuming)
   * §7.29
   */
  boolean markBatchResuming(String sessionId);

  /**
   * Clears the RESUMING flag for the session. §7.29
   * Called by WorkspaceManager.terminateSession() and on resume failure.
   */
  void clearBatchResuming(String sessionId);

IMPLEMENTATION:

  @Override
  public boolean markBatchResuming(String sessionId) {
      // putIfAbsent returns null if the key was NOT present → this
      // caller is first → safe to proceed.
      return resumingBatches.putIfAbsent(sessionId, Boolean.TRUE) == null;
  }

  @Override
  public void clearBatchResuming(String sessionId) {
      resumingBatches.remove(sessionId);
  }

──────────────────────────────────────────────────────────────────────────
Component 74-C2 — Guard in ApproveReflex.triggerBatchResume()
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../reflex/ApproveReflex.java

In triggerBatchResume(), WRAP the resume call:

  private String triggerBatchResume(
          String sessionId, String userId, String chatId,
          String agentRole, MessageContext context) throws Exception {

      // §7.29: Prevent double-resume from concurrent callback taps.
      if (!statefulAgentStore.markBatchResuming(sessionId)) {
          log.infof("[BATCH-RESUME-GUARD] session=%s already resuming — " +
                    "ignoring duplicate trigger.", sessionId);
          return "⏳ Already processing your approval. Please wait.";
      }

      try {
          final String resumeGoal =
              checkpointResumeHandler.resolveResumeGoal(sessionId, agentRole);
          context.setResumeSignal(true);
          context.setGoal(resumeGoal);
          AgentResult result = WorkspaceContext.runWithFullAutonomy(
              sessionId, userId, chatId, agentRole,
              () -> agentLoop.run(
                  sessionId, agentRole, resumeGoal, reasoningEngine, 5, context));
          return AgentResultFormatter.format(result);
      } finally {
          // Always clear — success or exception.
          statefulAgentStore.clearBatchResuming(sessionId);
      }
  }

SAME GUARD in DenyReflex.triggerBatchResume() — identical change.

──────────────────────────────────────────────────────────────────────────
Component 74-C3 — clearBatchResuming() on session termination
──────────────────────────────────────────────────────────────────────────

File: arcclaw-core/.../lifecycle/WorkspaceManager.terminateSession()

ADD at the end of terminateSession():
  // §7.29: Clear any in-flight batch resume flag for this session.
  statefulAgentStore.clearBatchResuming(sessionId);

This handles the case where terminateSession() is called before the
finally block in triggerBatchResume() (e.g., session GC during resume).


═══════════════════════════════════════════════════════════════════════════
PHASE 74 EXECUTION ORDER
═══════════════════════════════════════════════════════════════════════════

  Step 0    §7.27–§7.29 in Architecture_Invariants.md          (no deps)

  74-A1     TelegramChannelSender.editMessage() Path A or B    (no deps)
            — verify camel class availability first
  74-A2     Verify updateOrRemoveKeyboard() exercises new impl  (needs A1)

  74-B2     AgentResultFormatter.formatHitlMessage() plain text (no deps)
  74-B1     TelegramChannelSender.sendWithKeyboard() + escape() (needs B2)

  74-C1     StatefulAgentStore.markBatchResuming() / clear()    (no deps)
  74-C2     ApproveReflex + DenyReflex triggerBatchResume guard (needs C1)
  74-C3     WorkspaceManager.terminateSession() clear call      (needs C1)

  mvn clean verify (full build)
  Assert: 877+ tests green.


═══════════════════════════════════════════════════════════════════════════
PHASE 74 TEST MATRIX
═══════════════════════════════════════════════════════════════════════════

  TelegramChannelSenderEditMessageTest (new):
    - editMessage() with non-null markup → correct Bot API call or
      Camel model dispatched
    - editMessage() with null markup → empty inline_keyboard sent
      (removes buttons)

  AgentResultFormatterHitlTextTest (updated):
    - Batch HITL text contains no `**` bold markers
    - Single-tool HITL text is plain text
    - After escape(), `<` → `\<` and `>` → `\>` are present
    - No double-escaping when escape() called twice (idempotency check)

  BatchResumeRaceTest (new, unit):
    - First markBatchResuming(sessionId) → returns true
    - Second markBatchResuming(sessionId) → returns false
    - After clearBatchResuming(sessionId), markBatchResuming → true again
    - Concurrent calls from 2 threads → exactly one returns true


═══════════════════════════════════════════════════════════════════════════
KNOWN P74 LIMITATIONS (Tracked for P75+)
═══════════════════════════════════════════════════════════════════════════

  P75-1 — Partial-execute optimization:
           Pre-execute safe (non-HITL) tools before pausing, store
           their results in the checkpoint, execute only the HITL
           tool on resume. Reduces latency when 1-of-N needs approval.
           Requires a new checkpoint field for pre-executed results.

  P75-2 — Approval delegation:
           Allow a different user (admin) to approve on behalf of
           another. Requires a new ApproveDelegation record and
           userId check bypass in ApproveReflex.
```