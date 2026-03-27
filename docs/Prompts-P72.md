=====
PHASE 72 — Per-Tool HITL for Parallel Batches
ArcClaw P71 → P72
=====

Role: Senior Java Architect & Senior Java Developer
Goal: Allow users to approve or deny individual tools within a parallel
      batch HITL pause, rather than approving/denying the entire batch.

Context:
  P70/P71 introduced parallel tool execution. When any tool needs HITL
  approval, pauseEntireBatch() halts all N tools atomically.
  Users currently type `approve` or `deny` to accept/reject the whole
  batch. P72 adds per-tool granularity: `approve github`, `deny manage_schedule`.
  The existing `approve` / `deny` (no argument) continues to approve or
  deny ALL pending tools at once — no regression.

Architecture Context:
  Refer @Architecture_Invariants.md (mandatory).
  Add §7.19–§7.23 in Step 0 BEFORE writing any code.

Guiding Principles:
  1. All §7.1–§7.18 invariants remain in force.
  2. Zero new Maven dependencies.
  3. Zero DB migrations. Only nullable/default columns on AgentCheckpoint.
  4. Backward compatibility: `approve` / `deny` with no argument continues
     to approve / deny everything (single-tool AND entire parallel batch).
  5. A parallel batch that has partial denials MUST still reach the LLM
     with all N slots filled — denied tools get a sealed denial observation
     rather than being omitted from the batch step.
  6. callId MUST be non-null for all ParallelToolCall instances before
     the merge loop executes. Generate UUID fallback in ReasoningStepParser.
  7. 877+ existing tests remain green.


=====
STEP 0 — Architecture_Invariants.md §7.19–§7.23
=====

Add BEFORE writing any code:

  7.19 INVARIANT: ParallelToolCall.callId() MUST be non-null at the point
       the merge loop executes. ReasoningStepParser MUST generate a
       UUID fallback when ToolExecutionRequest.id() is null (Ollama). 
       Never use .equals() on a potentially-null callId — use Objects.equals().

  7.20 INVARIANT: A parallel batch HITL pause stores per-tool decision
       state in AgentCheckpoint.batchToolDecisions (TEXT, JSON) as:
         { "toolName": "PENDING" | "APPROVED" | "DENIED" }
       toolName is the key (not callId) because tool names appear in
       user-facing commands: `approve github`, `deny manage_schedule`.

  7.21 MUST: `approve` / `deny` with no argument MUST behave identically
       to P70/P71: approve or deny ALL pending tools atomically.
       Per-tool commands only activate when a tool name argument is given
       AND the current checkpoint has a batchToolDecisions field.

  7.22 INVARIANT: A partially-decided parallel batch (some tools APPROVED,
       some PENDING) MUST NOT resume. Resume fires only when ALL tools in
       the batch have a non-PENDING decision. Pending tools not explicitly
       decided when `approve` is typed are auto-approved.

  7.23 MUST: All N tools MUST appear in the completedBatchStep passed to
       the LLM. Denied tools carry observation:
       "User denied this tool execution." with success=false.
       The LLM sees the full picture and decides next steps.


=====
PROMPT — PHASE 72-A: callId Null-Safety (Quick Fix)
=====

------------
Component 72-A1 — Non-null callId in ReasoningStepParser
------------

File: arcclaw-core/.../reasoning/ReasoningStepParser.java

PROBLEM: ToolExecutionRequest.id() returns null for Ollama. The P71
merge loop calls d.callId().equals() — NPE when callId is null.

In the parallel detection branch (size > 1), when constructing each
ParallelToolCall:

  REPLACE:
    new ParallelToolCall(request.id(), request.name(), params, null, false)

  WITH:
    // §7.19: callId must be non-null for merge loop correctness.
    // Ollama does not provide tool call IDs — generate stable fallback.
    String callId = request.id() != null
        ? request.id()
        : request.name() + "-" + java.util.UUID.randomUUID();
    new ParallelToolCall(callId, request.name(), params, null, false)

NOTE: The fallback format "toolName-UUID" is prefixed with the tool name
for debuggability in logs. UUID suffix guarantees uniqueness within a
batch even when the same tool is called twice.

------------
Component 72-A2 — Objects.equals() in merge loop
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

In execute(), the merge loop uses:
  .filter(d -> d.callId().equals(original.callId()))

Although 72-A1 guarantees non-null at parse time, a deserialized
ParallelToolCall (from a checkpoint resume path) could still have a null
callId if the checkpoint was written before this fix.

REPLACE both occurrences of .callId().equals(original.callId()) with:
  .filter(d -> java.util.Objects.equals(d.callId(), original.callId()))

ADD import:
  import java.util.Objects;


=====
PROMPT — PHASE 72-B: Per-Tool HITL — Data Model
=====

------------
Component 72-B1 — AgentCheckpoint.batchToolDecisions field
------------

File: arcclaw-core/.../state/AgentCheckpoint.java  (Entity)

ADD one nullable TEXT column:

  @Column(name = "batch_tool_decisions", columnDefinition = "TEXT")
  private String batchToolDecisions; // JSON: {"github":"PENDING","manage_schedule":"PENDING"}

ADD generated getter/setter:
  public String getBatchToolDecisions() { return batchToolDecisions; }
  public void setBatchToolDecisions(String batchToolDecisions) {
      this.batchToolDecisions = batchToolDecisions;
  }

ADD convenience helper (non-JPA — plain logic):
  /**
   * Returns true if this is a parallel-batch HITL checkpoint with
   * per-tool decision state. §7.20
   */
  public boolean hasBatchToolDecisions() {
      return batchToolDecisions != null && !batchToolDecisions.isBlank();
  }

NOTE: Column is nullable. Single-tool checkpoints have null here.
The Hibernate schema auto-update (strategy: update) will ADD the column
on next start. No migration script required. Delete data folder on dev.

------------
Component 72-B2 — BatchToolDecision value constants
------------

File: arcclaw-core/.../agent/AgentSentinels.java

ADD three constants for per-tool decision values:

  /** Per-tool decision: tool has not yet been acted on. §7.20 */
  public static final String TOOL_DECISION_PENDING  = "PENDING";

  /** Per-tool decision: tool was explicitly approved by the user. §7.20 */
  public static final String TOOL_DECISION_APPROVED = "APPROVED";

  /** Per-tool decision: tool was explicitly denied by the user. §7.20 */
  public static final String TOOL_DECISION_DENIED   = "DENIED";

These are the only valid values in the batchToolDecisions JSON map.


=====
PROMPT — PHASE 72-C: Per-Tool HITL — Pause & Decision Storage
=====

------------
Component 72-C1 — ApprovalCheckpointManager.persistBatch()
──────────────────────────────────────────────────────════════════════════

File: arcclaw-core/.../loop/ApprovalCheckpointManager.java

ADD a new method alongside the existing persist():

  /**
   * Persists an AWAITING_APPROVAL checkpoint for a parallel tool batch.
   * Initialises per-tool decision state (§7.20) so ApproveReflex and
   * DenyReflex can record individual tool decisions before resuming.
   *
   * @param calls The full parallel batch (used to seed batchToolDecisions).
   */
  public AgentResult persistBatch(
          AgentExecutionContext context,
          String compositeName,
          String batchJson,
          List<ParallelToolCall> calls,
          List<ReasoningStep> history) {

      String sessionId  = context.sessionId();
      String agentRole  = context.agentRole();

      AgentCheckpoint cp = agentStore.load(sessionId, agentRole)
          .orElseGet(() -> {
              AgentCheckpoint c = new AgentCheckpoint();
              c.setSessionId(sessionId);
              c.setAgentRole(agentRole);
              return c;
          });

      // Seed per-tool decisions: all PENDING (§7.20)
      Map<String, String> decisions = new LinkedHashMap<>();
      calls.forEach(call -> decisions.put(
          call.toolName(), AgentSentinels.TOOL_DECISION_PENDING));

      String decisionsJson;
      try {
          decisionsJson = objectMapper.writeValueAsString(decisions);
      } catch (Exception e) {
          log.errorf("[PARALLEL-HITL] Failed to serialize decisions for " +
                     "session %s: %s", sessionId, e.getMessage());
          // Fall back to whole-batch persist — no per-tool granularity
          return persist(context, compositeName, batchJson, history);
      }

      cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
      cp.setPendingToolName(compositeName);
      cp.setPendingToolParametersJson(batchJson);
      cp.setBatchToolDecisions(decisionsJson);
      cp.setOriginalGoal(context.goal());
      cp.setUpdatedAt(LocalDateTime.now());
      agentStore.save(cp);

      log.infof("[STATE-MACHINE] Pausing session %s for parallel HITL: %s",
                sessionId, compositeName);
      return AgentResult.awaitingApproval(
          compositeName, batchJson, sessionId, List.copyOf(history));
  }

ADD imports:
  import java.util.LinkedHashMap;
  import java.util.Map;
  import com.arcclaw.core.agent.reasoning.ParallelToolCall;
  import com.fasterxml.jackson.databind.ObjectMapper;
  @Inject ObjectMapper objectMapper; // inject alongside agentStore

------------
Component 72-C2 — ParallelToolStepExecutor calls persistBatch()
------------

File: arcclaw-core/.../loop/ParallelToolStepExecutor.java

In pauseEntireBatch(), REPLACE:
  return StepDecision.terminal(
      checkpointManager.persist(context, compositeName, batchJson, history));

WITH:
  // §7.20: Use persistBatch() to seed per-tool decision state.
  return StepDecision.terminal(
      checkpointManager.persistBatch(
          context, compositeName, batchJson, calls, history));


=====
PROMPT — PHASE 72-D: Per-Tool HITL — Command Parsing
=====

------------
Component 72-D1 — ChannelCommand.Approve / Deny extended with toolName
------------

File: arcclaw-channel-api/.../ChannelCommand.java

CURRENT records:
  record Approve(Optional<String> cronId) implements ChannelCommand {}
  record Deny(Optional<String>   cronId) implements ChannelCommand {}

REPLACE WITH:
  /**
   * approve                    → cronId=empty, toolName=empty (approve all)
   * approve cron123            → cronId=present, toolName=empty (existing cron path)
   * approve github             → cronId=empty, toolName=present (per-tool P72)
   * /approve github            → same
   */
  record Approve(
      Optional<String> cronId,
      Optional<String> toolName
  ) implements ChannelCommand {}

  record Deny(
      Optional<String> cronId,
      Optional<String> toolName
  ) implements ChannelCommand {}

UPDATE parse() logic for Approve and Deny:

  The argument disambiguation rule (§7.21):
    NO argument        → approve/deny all pending (cronId=empty, toolName=empty)
    ONE argument:
      If it looks like a cron ID (contains "-" AND the active checkpoint
      does NOT have batchToolDecisions) → cronId=present, toolName=empty
      Otherwise                         → cronId=empty, toolName=present

  IMPORTANT: The parse() method has NO access to checkpoint state. It
  cannot look up whether a checkpoint has batchToolDecisions. Therefore:
    - Keep the existing cronId heuristic: if the argument contains "-",
      treat it as cronId (existing cron IDs are UUID-like).
    - If the argument has NO "-" (e.g., "github", "manage_schedule"),
      treat it as toolName.
    - This is 99% correct since tool names never contain "-" in practice.
      If a tool name does contain "-", the disambiguator in ApproveReflex
      (which HAS checkpoint access) performs the final triage.

  EXAMPLE parse logic:
    private static Optional<String> parseCronId(String arg) {
        return (arg != null && arg.contains("-"))
            ? Optional.of(arg) : Optional.empty();
    }
    private static Optional<String> parseToolName(String arg) {
        return (arg != null && !arg.contains("-") && !arg.isBlank())
            ? Optional.of(arg) : Optional.empty();
    }

    For "approve github":
      cronId  = parseCronId("github")   = Optional.empty()
      toolName = parseToolName("github") = Optional.of("github")

    For "approve 550e8400-e29b-41d4-a716-446655440000":
      cronId  = parseCronId(uuid)  = Optional.of(uuid)
      toolName = parseToolName(uuid) = Optional.empty()

BACKWARD COMPATIBILITY: All existing callers of Approve/Deny that only
use cronId() continue to work. toolName() defaults to Optional.empty()
so no call sites break. Only ApproveReflex and DenyReflex need to be
updated to handle toolName.

UPDATE all existing construction sites:
  new Approve(cronIdOpt)  → new Approve(cronIdOpt, Optional.empty())
  new Deny(cronIdOpt)     → new Deny(cronIdOpt,   Optional.empty())


=====
PROMPT — PHASE 72-E: Per-Tool HITL — Reflex Decision Recording
=====

------------
Component 72-E1 — StatefulAgentStore.updateBatchToolDecision()
------------

File: arcclaw-core/.../state/StatefulAgentStore.java  (interface + impl)

ADD method to interface and implementation:

  /**
   * Atomically sets one tool's decision in the checkpoint's
   * batchToolDecisions JSON map. §7.20
   *
   * @param sessionId   the active session
   * @param agentRole   the agent role
   * @param toolName    the tool whose decision to update
   * @param decision    APPROVED or DENIED (§7.20)
   * @return true if the checkpoint was found and updated; false otherwise
   */
  boolean updateBatchToolDecision(
      String sessionId, String agentRole,
      String toolName, String decision);

IMPLEMENTATION (in StatefulAgentStoreImpl or equivalent):
  1. Load checkpoint for (sessionId, agentRole) with AWAITING_APPROVAL status
  2. If not found or no batchToolDecisions → return false
  3. Deserialize batchToolDecisions JSON → Map<String, String>
  4. If toolName is not a key in the map → return false
     (prevents approval of tools not in this batch)
  5. map.put(toolName, decision)
  6. Re-serialize → checkpoint.setBatchToolDecisions(json)
  7. agentStore.save(checkpoint)
  8. return true

ADD helper to StatefulAgentStore:
  /**
   * Returns true if all tools in the batchToolDecisions map have
   * a non-PENDING decision. §7.22
   */
  boolean isAllBatchToolsDecided(String sessionId, String agentRole);

IMPLEMENTATION: load checkpoint, deserialize map, check none == "PENDING".

------------
Component 72-E2 — ApproveReflex per-tool path
------------

File: arcclaw-core/.../reflex/ApproveReflex.java

In execute(), after the cron-ID fast-path check and BEFORE the existing
session checkpoint approval path, ADD a per-tool approval branch:

  // Per-tool approval: `approve github` where toolName.isPresent()
  if (approve.toolName().isPresent()) {
      return handlePerToolApproval(
          approve.toolName().get(), sessionId, userId, chatId, context);
  }
  // ... existing whole-batch approval path unchanged below ...

ADD private method handlePerToolApproval():

  private String handlePerToolApproval(
          String toolName, String sessionId, String userId,
          String chatId, MessageContext context) {

      // Find active parallel-batch checkpoint
      String activeRole = null;
      for (AgentRoleEnum role : AgentRoleEnum.values()) {
          Optional<AgentCheckpoint> cpOpt =
              statefulAgentStore.load(sessionId, role.toScopedName());
          if (cpOpt.isPresent()
                  && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL
                  && cpOpt.get().hasBatchToolDecisions()) {
              activeRole = role.toScopedName();
              break;
          }
      }

      if (activeRole == null) {
          // No parallel batch checkpoint — treat as unknown command
          return "No parallel batch awaiting per-tool approval for this session.";
      }

      // Record the APPROVED decision for this tool
      boolean updated = statefulAgentStore.updateBatchToolDecision(
          sessionId, activeRole, toolName, AgentSentinels.TOOL_DECISION_APPROVED);

      if (!updated) {
          return String.format(
              "Tool '%s' is not part of the pending batch, " +
              "or was already decided. Use `approve` to approve all.",
              toolName);
      }

      // Check if all tools now have decisions (§7.22)
      if (statefulAgentStore.isAllBatchToolsDecided(sessionId, activeRole)) {
          // All decided → trigger full batch resume
          return triggerBatchResume(sessionId, userId, chatId, activeRole, context);
      }

      // Still some PENDING tools — inform user
      AgentCheckpoint cp =
          statefulAgentStore.load(sessionId, activeRole).orElseThrow();
      List<String> stillPending = pendingToolNames(cp);
      return String.format(
          "✅ Approved `%s`. Still waiting for: %s\n" +
          "Type `approve <toolName>` or `approve` to approve all remaining.",
          toolName,
          stillPending.stream()
              .map(t -> "`" + t + "`")
              .collect(java.util.stream.Collectors.joining(", ")));
  }

ADD private helper pendingToolNames():

  private List<String> pendingToolNames(AgentCheckpoint cp) {
      if (!cp.hasBatchToolDecisions()) return List.of();
      try {
          Map<String, String> map = objectMapper.readValue(
              cp.getBatchToolDecisions(),
              new com.fasterxml.jackson.core.type.TypeReference<>() {});
          return map.entrySet().stream()
              .filter(e -> AgentSentinels.TOOL_DECISION_PENDING.equals(e.getValue()))
              .map(Map.Entry::getKey)
              .toList();
      } catch (Exception e) {
          return List.of();
      }
  }

ADD private helper triggerBatchResume() — re-uses existing approval logic:

  private String triggerBatchResume(
          String sessionId, String userId, String chatId,
          String agentRole, MessageContext context) {
      // Same as the existing whole-batch approve path:
      // context.setResumeSignal(true), context.setGoal(resolvedGoal),
      // agentLoop.run(sessionId, agentRole, resumeGoal, ..., context)
      final String resumeGoal =
          checkpointResumeHandler.resolveResumeGoal(sessionId, agentRole);
      context.setResumeSignal(true);
      context.setGoal(resumeGoal);
      AgentResult result = WorkspaceContext.runWithFullAutonomy(
          sessionId, userId, chatId, agentRole,
          () -> agentLoop.run(
              sessionId, agentRole, resumeGoal, reasoningEngine, 5, context));
      return AgentResultFormatter.format(result);
  }

ALSO UPDATE the existing "approve all" path (no toolName argument):
  When approve.toolName() is absent AND the checkpoint hasBatchToolDecisions():
  → Auto-approve all PENDING tools (set all PENDING → APPROVED)
  → Then trigger batch resume
  This maintains §7.21 (no-arg approve still works for whole batch).

  ADD this block BEFORE the existing checkpoint execution code:
    if (approve.toolName().isEmpty()
            && activeCheckpoint.hasBatchToolDecisions()) {
        // Approve all remaining PENDING tools at once (§7.21)
        autoApproveAllPending(sessionId, activeRole);
        return triggerBatchResume(sessionId, userId, chatId, activeRole, context);
    }

  ADD private helper autoApproveAllPending():
    private void autoApproveAllPending(String sessionId, String agentRole) {
        AgentCheckpoint cp =
            statefulAgentStore.load(sessionId, agentRole).orElseThrow();
        pendingToolNames(cp).forEach(toolName ->
            statefulAgentStore.updateBatchToolDecision(
                sessionId, agentRole, toolName,
                AgentSentinels.TOOL_DECISION_APPROVED));
    }

------------
Component 72-E3 — DenyReflex per-tool path (mirrors ApproveReflex)
------------

File: arcclaw-core/.../reflex/DenyReflex.java

ADD the same per-tool branch after the cron-ID fast-path:

  if (deny.toolName().isPresent()) {
      return handlePerToolDenial(
          deny.toolName().get(), sessionId, userId, chatId, context);
  }

ADD private method handlePerToolDenial() — mirrors handlePerToolApproval()
with TOOL_DECISION_DENIED instead of TOOL_DECISION_APPROVED, and message:
  "🚫 Denied `%s`. ..."

ALSO UPDATE the "deny all" path (no toolName, hasBatchToolDecisions):
  → Set all PENDING → DENIED
  → Trigger batch resume (denied tools get denial observation, §7.23)
  → Do NOT fall through to the existing single-tool denial path which
    sets CheckpointStatus.FAILED — the batch resume handler handles status.


=====
PROMPT — PHASE 72-F: Per-Tool HITL — Decision-Aware Resume
=====

------------
Component 72-F1 — CheckpointResumeHandler.resumeParallelBatch()
------------

File: arcclaw-core/.../loop/CheckpointResumeHandler.java

CURRENT resumeParallelBatch() executes ALL calls unconditionally.

REPLACE the tool execution loop with a decision-aware loop:

  // §7.20: Read per-tool decisions if present on the checkpoint.
  Map<String, String> decisions = Map.of(); // default: no per-tool state
  if (checkpoint.hasBatchToolDecisions()) {
      try {
          decisions = objectMapper.readValue(
              checkpoint.getBatchToolDecisions(),
              new TypeReference<Map<String, String>>() {});
      } catch (Exception e) {
          log.warnf("[PARALLEL-HITL-RESUME] session=%s: " +
                    "Failed to parse batchToolDecisions: %s — " +
                    "defaulting to approve-all.",
                    sessionId, e.getMessage());
          // decisions stays Map.of() → approve-all fallback
      }
  }

  List<ParallelToolCall> completed = new ArrayList<>();
  for (ParallelToolCall call : calls) {
      String decision = decisions.getOrDefault(
          call.toolName(), AgentSentinels.TOOL_DECISION_APPROVED);

      if (AgentSentinels.TOOL_DECISION_DENIED.equals(decision)) {
          // §7.23: Denied tool → sealed observation, success=false
          log.infof("[PARALLEL-HITL-RESUME] session=%s tool=%s DENIED by user",
                    sessionId, call.toolName());
          completed.add(call.withResult(
              "User denied this tool execution.", false));
          continue;
      }

      // APPROVED or PENDING (auto-approve) — execute normally
      try {
          ToolResponse response = WorkspaceContext.runWithFullAutonomy(
              sessionId, WorkspaceContext.userId,
              WorkspaceContext.chatId, agentRole,
              () -> toolRegistry.invokeTool(
                  call.toolName(), call.paramsAsString(), sessionId));
          completed.add(call.withResult(response.output(), response.success()));
      } catch (Exception e) {
          log.warnf("[PARALLEL-HITL-RESUME] session=%s tool=%s failed: %s",
                    sessionId, call.toolName(), e.getMessage());
          completed.add(call.withResult(
              "Tool execution failed on resume: " + e.getMessage(), false));
      }
  }

  // Rebuild completedJson and completedStep — same as before
  // history.add(completedStep), checkpoint cleanup, return null unchanged


=====
PROMPT — PHASE 72-G: Per-Tool HITL — User-Facing Messages
=====

------------
Component 72-G1 — AgentResultFormatter.formatBatchHitl()
------------

File: arcclaw-core/.../util/AgentResultFormatter.java

CURRENT formatCronHitl() constructs the HITL message for cron jobs.
For interactive sessions, AgentResult.awaitingApproval.toolName() carries
the composite name `parallel:github,manage_schedule`.

ADD a detection + format method:

  /**
   * Returns a user-facing HITL message. Detects parallel batch prefix
   * and formats per-tool instructions. §7.21 §7.22
   */
  public static String formatHitlMessage(String toolName) {
      if (toolName != null && toolName.startsWith("parallel:")) {
          String[] tools = toolName.substring("parallel:".length()).split(",");
          StringBuilder sb = new StringBuilder();
          sb.append("⏳ I need your approval to run ")
            .append(tools.length)
            .append(" tool").append(tools.length > 1 ? "s" : "").append(":\n");
          for (String tool : tools) {
              sb.append("  • `").append(tool.strip()).append("`\n");
          }
          sb.append("\n");
          sb.append("**Per-tool:** `approve <toolName>` or `deny <toolName>`\n");
          sb.append("**All at once:** `approve` (approve all) or `deny` (deny all)");
          return sb.toString();
      }
      // Single-tool (existing format unchanged)
      return String.format(
          "⏳ I need your approval to run `%s`.\n" +
          "Type `approve` to proceed or `deny` to cancel.",
          toolName);
  }

INTEGRATE in AgentResultFormatter.format(AgentResult):
  In the AwaitingApproval branch:
    REPLACE:
      return "⏳ Awaiting approval for tool: " + aa.toolName();
    WITH:
      return formatHitlMessage(aa.toolName());

UPDATE formatCronHitl() to reuse formatHitlMessage() for the tool part:
  The cron HITL message currently is:
    "⏳ Background task awaiting approval: cronId=X, goal=Y, tool=Z"
  Keep the cron prefix, but append formatHitlMessage(result.toolName())
  for the approval instructions.


=====
PHASE 72 EXECUTION ORDER
=====

Execute in this strict dependency order:

  Step 0    Architecture_Invariants.md §7.19–§7.23             (no deps)
  72-A1     callId non-null in ReasoningStepParser              (no deps)
  72-A2     Objects.equals() in merge loop                      (needs A1)
  72-B1     AgentCheckpoint.batchToolDecisions field            (no deps)
  72-B2     AgentSentinels PENDING/APPROVED/DENIED constants    (no deps)
  72-C1     ApprovalCheckpointManager.persistBatch()            (needs B1, B2)
  72-C2     ParallelToolStepExecutor calls persistBatch()       (needs C1)
  72-D1     ChannelCommand.Approve/Deny + toolName field        (no deps)
  72-E1     StatefulAgentStore.updateBatchToolDecision()        (needs B1, B2)
  72-E2     ApproveReflex per-tool path                         (needs D1, E1)
  72-E3     DenyReflex per-tool path                            (needs D1, E1)
  72-F1     CheckpointResumeHandler decision-aware resume       (needs B1, B2)
  72-G1     AgentResultFormatter.formatHitlMessage()            (no deps)

  mvn clean verify (full build)
  Assert: 877+ tests green.
  Assert: All new P72 tests added and green.


=====
PHASE 72 TEST MATRIX
=====

  ParallelToolCallTest (new):
    - callId null input → UUID fallback generated, non-null
    - merge loop: Objects.equals handles null callId from old checkpoints

  BatchToolDecisionTest (new, unit):
    - persistBatch() seeds all tools as PENDING
    - updateBatchToolDecision("github", APPROVED) → stored correctly
    - updateBatchToolDecision("unknown_tool", APPROVED) → returns false
    - isAllBatchToolsDecided: all APPROVED → true
    - isAllBatchToolsDecided: one PENDING → false

  ApproveReflexBatchTest (new):
    - `approve github` on parallel batch checkpoint
      → updates decision, not all decided → returns "Still waiting for"
    - `approve manage_schedule` after github already approved
      → all decided → triggers resume → returns agent result
    - `approve` (no args) on batch checkpoint
      → auto-approves all PENDING → triggers resume
    - `approve github` on single-tool checkpoint
      → "No parallel batch awaiting per-tool approval"

  DenyReflexBatchTest (new):
    - `deny manage_schedule` → marks DENIED, not all decided → "Still waiting"
    - After remaining approved → resume fires with denial observation

  CheckpointResumeHandlerDecisionTest (new):
    - All APPROVED → all tools execute
    - Mixed APPROVED/DENIED → denied tools get sealed observation
    - hasBatchToolDecisions=false → approve-all fallback (P70 compat)
    - One tool throws on execute → withResult(failure, false), others unaffected

  ChannelCommandParseTest (updated):
    - "approve"            → Approve(empty, empty)
    - "approve github"     → Approve(empty, "github")
    - "approve 550e8400-e29b-41d4-a716-446655440000" → Approve("550e8400...", empty)
    - "deny manage_schedule" → Deny(empty, "manage_schedule")


=====
KNOWN P72 LIMITATIONS (Tracked for P73+)
=====

  P73-1 — Telegram inline keyboard for per-tool approval:
           Currently text-based commands. P73 can add TelegramInlineKeyboard
           with one row per tool: [✅ github] [🚫 github] [✅ manage_schedule]...
           Requires Telegram callback_query handler and bot API editMessageReplyMarkup.

  P73-2 — Approval timeout for parallel batch:
           If a user never responds to a parallel HITL pause, the session
           stays in AWAITING_APPROVAL indefinitely. P73 should add a
           WorkspaceManager GC check for stale AWAITING_APPROVAL checkpoints
           (e.g., > 24h since updatedAt) and auto-deny them.

  P73-3 — Partial-execute optimization:
           Currently ALL tools are paused when ANY needs HITL, even
           tools that don't need approval. P73 could pre-execute safe
           tools before pausing, store their results in the checkpoint,
           and only execute the HITL tool on resume. Significant complexity.

 The key decisions in P72 are driven by the P68 source confirming that `ApproveReflex`/`DenyReflex` own all checkpoint state transitions, `ChannelCommand.Approve` already has an `Optional<String> cronId` pattern that P72 extends to `toolName`, and `CheckpointResumeHandler` is the single resume path so the decision-aware logic belongs there cleanly. 