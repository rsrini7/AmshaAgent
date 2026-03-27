Good news first: `ReplyNode.ApprovalPrompt` was already in the sealed hierarchy from P68, so P73 compiles cleanly.  The `AgentResultFormatter` prefix fix (`"parallel:"` vs `"__parallel__:"`) was correctly applied in the P73 diff as well.  Here is the full end-to-end review: 

***

## Critical — Keyboard Silently Never Appears

**`TelegramChannelSender.sendWithKeyboard()` uses a non-existent Camel header.** 

```java
producerTemplate.sendBodyAndHeaders("telegram:bots", text, Map.of(
    TelegramConstants.TELEGRAM_CHAT_ID, chatId,
    "CamelTelegramReplyMarkup", keyboardJson   // ← not a real Camel header
));
```

`"CamelTelegramReplyMarkup"` is not a documented `camel-quarkus-telegram` header. The message sends successfully (no error thrown) but Telegram never receives the keyboard — it is silently dropped. The correct approach uses Camel's own model classes, which are available in the existing `camel-quarkus-telegram` dependency with no new Maven artifact needed:

```java
// Fix: use Apache Camel's own model, no new dependency needed
import org.apache.camel.component.telegram.model.OutgoingTextMessage;
import org.apache.camel.component.telegram.model.InlineKeyboardMarkup;
import org.apache.camel.component.telegram.model.InlineKeyboardButton;

public void sendWithKeyboard(ChannelUser recipient, String text, String keyboardJson) {
    if (keyboardJson == null || keyboardJson.isBlank()) {
        sendRaw(recipient.channelId(), text);
        return;
    }
    // Deserialize the keyboardJson we already built back into Camel model objects
    // OR build the keyboard directly here from toolNames instead of via JSON round-trip
    OutgoingTextMessage msg = new OutgoingTextMessage();
    msg.setText(text);
    msg.setParseMode("MarkdownV2");
    // Construct InlineKeyboardMarkup from keyboardJson via objectMapper
    InlineKeyboardMarkup markup = objectMapper.readValue(keyboardJson, InlineKeyboardMarkup.class);
    msg.setReplyMarkup(markup);
    producerTemplate.sendBodyAndHeader(
        "telegram:bots", msg, TelegramConstants.TELEGRAM_CHAT_ID, recipient.channelId());
}
```

**Alternatively**: refactor `BatchHitlKeyboardBuilder` to produce Camel model objects directly (instead of JSON strings), removing the JSON round-trip entirely. This is cleaner and should be the P73 fix.

***

## Critical — `editMessage()` Sends a New Message

The `messageId` parameter is completely ignored — a new message is sent to the chat for every per-tool decision. 

```java
public void editMessage(String chatId, int messageId, String newText, String keyboardJson) {
    producerTemplate.sendBodyAndHeaders("telegram:bots", newText != null ? newText : "", Map.of(
        TelegramConstants.TELEGRAM_CHAT_ID, chatId,
        "CamelTelegramReplyMarkup", keyboardJson == null ? "" : keyboardJson
    ));
    // messageId is IGNORED — this is a send, not an edit
}
```

The comment in the file acknowledges this but the method is still wired up to `updateOrRemoveKeyboard()`. Until this is properly fixed, replace it with a no-op stub and track it for P74:

```java
public void editMessage(String chatId, int messageId, String newText, String keyboardJson) {
    // P74-3: editMessageReplyMarkup not yet supported via camel-telegram.
    // Proper implementation requires OutgoingEditMessage or direct Bot API call.
    log.debugf("editMessage: deferred to P74 (chatId=%s, msgId=%d)", chatId, messageId);
}
```

This avoids the spurious "Please review remaining operations:" / "Approval process completed." messages polluting the chat.

***

## High — Double-Escaped Newlines in Stale Observer

`TelegramStaleApprovalObserver.java` displays literal `\n` text instead of line breaks: 

```java
// BUG: \\\\n in Java source = \\n in the string = "\n" displayed as text in Telegram
"⏱ Your approval window for `%s` has expired (>%dh).\\\\n" +
"The pending tool execution was automatically cancelled.\\\\n" +

// FIX:
"⏱ Your approval window for `%s` has expired (>%dh).\n" +
"The pending tool execution was automatically cancelled.\n" +
```

This same bug exists in the `ApproveReflex` and `DenyReflex` "Still waiting for" messages (carried over from P72). All three need the same fix. 

***

## High — Missing `answerCallbackQuery`

Every button tap in `TelegramCallbackHandler` leaves a persistent loading spinner on the button. Telegram requires answering the callback within a few seconds or the UI shows an error. 

`camel-quarkus-telegram` does not have a direct `answerCallbackQuery` method in its Camel model layer. Add a direct HTTP call using the existing `producerTemplate` or a Quarkus `RestClient` to `https://api.telegram.org/bot{token}/answerCallbackQuery`:

```java
// In TelegramCallbackHandler, at end of handleCallback():
private void answerCallback(String callbackQueryId) {
    try {
        // camel-telegram does not wrap answerCallbackQuery natively.
        // Use a simple HTTP POST to the Bot API.
        String url = "https://api.telegram.org/bot" + botToken
                   + "/answerCallbackQuery?callback_query_id=" + callbackQueryId;
        producerTemplate.sendBody("netty-http:" + url, "");
    } catch (Exception e) {
        log.warnf("Failed to answer callback query %s: %s", callbackQueryId, e.getMessage());
    }
}
```

Or, since `netty-http` may not be available, use `@Inject` of a Quarkus `WebClient` / `RestTemplate`. The simplest dependency-free option: add `quarkus-rest-client-reactive` (already a Quarkus BOM artifact, no version needed) and define a one-method `@RegisterRestClient` interface for the Bot API.

***

## Medium — JPQL Inline Enum Reference

`StatefulAgentStoreImpl.findStaleAwaitingApproval()` embeds the enum class path as a literal string in the JPQL query. Hibernate on SQLite accepts this in some versions but it is not portable JPA. 

```java
// BUG — non-portable JPQL
"WHERE c.status = com.arcclaw.core.state.CheckpointStatus.AWAITING_APPROVAL "

// FIX — matches the pattern used everywhere else in this file
"WHERE c.status = :status AND c.updatedAt < :cutoff"
// ...
.setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
.setParameter("cutoff", LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC))
```

***

## Medium — `markApprovalTimedOut()` Entity Merge Overwrites Decisions

In `SessionReaper.markApprovalTimedOut()`, `updateBatchToolDecision()` runs in a `REQUIRES_NEW` transaction and persists the DENIED decisions. Then `em.merge(cp)` follows — using the old `cp` object that still has `batchToolDecisions` set to all-PENDING. This overwrites the changes from `updateBatchToolDecision()`. 

The functional impact is low (the session is terminated so the decisions are never read), but the DB state is inconsistent. Fix by reloading `cp` after the decision updates:

```java
@Transactional(Transactional.TxType.REQUIRED)
void markApprovalTimedOut(AgentCheckpoint cp) {
    if (cp.hasBatchToolDecisions()) {
        BatchDecisionUtil.pendingToolNames(cp, objectMapper).forEach(toolName ->
            agentStore.updateBatchToolDecision(
                cp.getSessionId(), cp.getAgentRole(), toolName,
                AgentSentinels.TOOL_DECISION_DENIED));
    }
    // Reload to pick up the decisions written by REQUIRES_NEW transactions above
    AgentCheckpoint fresh = agentStore.load(cp.getSessionId(), cp.getAgentRole())
        .orElse(cp);
    fresh.setStatus(CheckpointStatus.FAILED);
    fresh.setFailedAt(Instant.now());
    fresh.setFailReason("Approval timed out after " + hitlTimeoutHours + " hours.");
    em.merge(fresh);
    // ... fire event
}
```

***

## Medium — `CheckpointResumeHandler` Throws Before Catch

When `getBatchToolDecisions()` returns null, `objectMapper.readValue(null, ...)` throws a `JsonProcessingException` (null input). The catch block handles it, so behaviour is correct — but it silently creates and discards an exception on every single-tool checkpoint resume. 

**One-line fix:** gate the parse behind `hasBatchToolDecisions()`:

```java
// REPLACE:
Map<String, String> decisions = Map.of();
try {
    decisions = objectMapper.readValue(checkpoint.getBatchToolDecisions(), ...);
} catch (Exception e) { ... }

// WITH:
Map<String, String> decisions = Map.of();
if (checkpoint.hasBatchToolDecisions()) {
    try {
        decisions = objectMapper.readValue(checkpoint.getBatchToolDecisions(), ...);
    } catch (Exception e) { ... }
}
```

***

## Low / Style

| # | Location | Issue |
|---|---|---|
| L1 | `TelegramChannelSender` | `sendRaw(String, String)` changed from `private` → `public` — leaks internal API, only `TelegramCallbackHandler` needs it; pass a `ChannelUser` instead |
| L2 | `TelegramCallbackHandler.getSessionId()` | Last line casts without check: `((DenyAll) event).sessionId()` — will throw `ClassCastException` if a new sealed type is ever added. Use `switch` for exhaustiveness. |
| L3 | `TelegramChannelRoute` | Missing `.end()` after the `.choice()` block; safe in Camel 4.x when route-terminal but add for clarity |
| L4 | `TelegramStaleApprovalObserver` | `hitlTimeoutHours` hardcoded as `24` — should reference the configured value from `SessionReaper` or a shared config property |
| L5 | `TelegramCallbackHandler` | `new MessageContext(chatId, user, null)` — null message body may cause NPE in pipeline handlers that call `context.getMessage()` downstream |

***

## What's Correct

- `BatchDecisionUtil` extraction (M1 from P72 review) — done correctly in both reflexes 
- `AgentResultFormatter` prefix fix (`"parallel:"`) — correct 
- `ReasoningStepParser` callId fallback — correct 
- `StatefulAgentStoreImpl.doUpdateBatchToolDecision()` with `REQUIRES_NEW` — correct pattern 
- `TelegramChannelRoute` choice routing for `IncomingCallbackQuery` — correct architecture 
- `BatchHitlKeyboardBuilder.parseCallback()` split logic — correct for the format produced 
- `WorkspaceManagerGcSweepTest` mock migration — correct 