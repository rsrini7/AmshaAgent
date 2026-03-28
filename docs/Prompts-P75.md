=====
PHASE 75 — Safe-Tool Pre-Execution + Approval Delegation + P74 Housekeeping
ArcClaw P74 → P75  [FINAL — Refactoring & Enhancing Segment]
=====

Role: Senior Java Architect & Senior Java Developer

Goals:
  P75-H  Fix the three minor notes left over from P74 review.
  P75-A  Pre-execute safe (non-HITL) tools in a parallel batch before
         pausing, store their results in the checkpoint, skip them on
         resume. Reduces user-perceived latency when only 1-of-N tools
         needs approval.
  P75-B  Approval delegation — allow a configured admin user to type
         /approve or /deny on behalf of another user's session.

Architecture Context:
  Refer @Architecture_Invariants.md (mandatory).
  Add §7.30–§7.33 in Step 0 BEFORE writing any code.

Guiding Principles:
  1. All §7.1–§7.29 invariants remain in force.
  2. Zero new Maven dependencies.
  3. 892+ existing tests remain green.
  4. P75-A requires a new DB column — add it via a Flyway-style
     columnDefinition on the JPA entity and let Hibernate schema
     update handle it (consistent with the rest of the project).


=====
STEP 0 — Architecture_Invariants.md §7.30–§7.33
=====

  7.30 INVARIANT: Within a parallel batch, tools whose autonomy level
       permits execution WITHOUT human approval (autonomy >= FULL) MUST
       be pre-executed before the HITL pause. Their results MUST be
       stored in AgentCheckpoint.preExecutedResults (TEXT, JSON) as
       toolName → ToolResponse. On resume, CheckpointResumeHandler
       MUST use pre-stored results instead of re-executing those tools.

  7.31 INVARIANT: A tool is considered "pre-executable" if and only if:
       (a) the current session's autonomy level is FULL_AUTONOMY, OR
       (b) the tool name appears in arcclaw.hitl.pre-executable-tools
           config list. Default list is empty. Safe tools are never
           automatically inferred — they must be explicitly configured.
       This prevents accidental side effects from misclassification.

  7.32 MUST: The HITL pause message shown to the user MUST list ONLY
       the tools that still require approval. Pre-executed tools MUST
       NOT appear in the keyboard or the approval text. The Approve All
       / Deny All buttons operate only on the PENDING tools.

  7.33 INVARIANT: Approval delegation is scoped to a single session.
       An admin (user listed in arcclaw.hitl.delegate-approver-ids)
       may issue /approve or /deny for ANY active AWAITING_APPROVAL
       session by providing the sessionId: "/approve <sessionId>".
       The delegate action is audit-logged with both the admin userId
       and the original session owner's userId. Delegation MUST NOT
       bypass per-tool granularity — /approve <sessionId> <toolName>
       is also valid.


=====
PHASE 75-H — P74 Housekeeping (do these first, they are trivial)
=====

------------
75-H1 — Fix indentation in handleCronApproval() / handleCronDenial()
------------

Files: ApproveReflex.java, DenyReflex.java

The try-finally block added in P74 left inner statements at mismatched
indent. Re-indent the entire try body to 12 spaces (3 levels) so the
code reads correctly. No logic change — compile and verify tests green.

------------
75-H2 — Remove dead @Mock Logger in TelegramChannelSenderEditMessageTest
------------

File: TelegramChannelSenderEditMessageTest.java

  REMOVE: @Mock Logger log;

The Logger field in TelegramChannelSender is private static final —
@InjectMocks cannot replace it. The mock declaration is dead code.
No test logic changes needed.


=====
PHASE 75-A — Safe-Tool Pre-Execution Before HITL Pause
=====

------------
75-A1 — New config + AgentCheckpoint column
------------

application.yaml (all profiles):
  arcclaw:
    hitl:
      pre-executable-tools: ""   # comma-separated, empty = none

AgentCheckpoint.java — add:
  /**
   * JSON map of toolName → serialized ToolResponse for tools that
   * were pre-executed before the HITL pause. §7.30
   */
  @Column(name = "pre_executed_results", columnDefinition = "TEXT")
  private String preExecutedResults;

  public String getPreExecutedResults() { return preExecutedResults; }
  public void setPreExecutedResults(String v) { this.preExecutedResults = v; }

  public boolean hasPreExecutedResults() {
      return preExecutedResults != null && !preExecutedResults.isBlank();
  }

------------
75-A2 — PreExecutionFilter utility
------------

New file: arcclaw-core/.../util/PreExecutionFilter.java

  /**
   * Classifies tools in a parallel batch as pre-executable or HITL-pending.
   * §7.31
   */
  public final class PreExecutionFilter {

      private PreExecutionFilter() {}

      public record SplitResult(
          List<ParallelToolCall> preExecutable,
          List<ParallelToolCall> hitlPending
      ) {}

      /**
       * Splits calls into pre-executable vs HITL-pending.
       * A tool is pre-executable only if its name appears in
       * configuredSafeTools OR autonomyLevel == FULL_AUTONOMY. §7.31
       */
      public static SplitResult split(
              List<ParallelToolCall> calls,
              AutonomyLevel sessionAutonomy,
              Set<String> configuredSafeTools) {

          List<ParallelToolCall> pre = new ArrayList<>();
          List<ParallelToolCall> hitl = new ArrayList<>();

          for (ParallelToolCall call : calls) {
              if (sessionAutonomy == AutonomyLevel.FULL_AUTONOMY
                      || configuredSafeTools.contains(call.toolName())) {
                  pre.add(call);
              } else {
                  hitl.add(call);
              }
          }
          return new SplitResult(pre, hitl);
      }
  }

------------
75-A3 — ParallelToolStepExecutor: pre-execute before pausing
------------

File: ParallelToolStepExecutor.java

Inject:
  @ConfigProperty(name = "arcclaw.hitl.pre-executable-tools",
                  defaultValue = "")
  Optional<List<String>> preExecutableTools;

In the method that currently calls
checkpointManager.persistBatch(...):

  REPLACE the direct persistBatch call with:

  private StepDecision handleHitlBatch(
          AgentExecutionContext context,
          String compositeName,
          String batchJson,
          List<ParallelToolCall> calls,
          List<ReasoningStep> history) {

      Set<String> safeTools = preExecutableTools
          .map(l -> new HashSet<>(l))
          .orElse(Set.of());

      AutonomyLevel autonomy = context.autonomyLevel(); // expose if not already

      PreExecutionFilter.SplitResult split =
          PreExecutionFilter.split(calls, autonomy, safeTools);

      // §7.31: If nothing is pre-executable, persist normally
      if (split.preExecutable().isEmpty()) {
          return StepDecision.terminal(
              checkpointManager.persistBatch(
                  context, compositeName, batchJson, calls, history));
      }

      // Pre-execute the safe tools now
      Map<String, String> preResults = new LinkedHashMap<>();
      List<ParallelToolCall> completedPre = new ArrayList<>();

      for (ParallelToolCall call : split.preExecutable()) {
          try {
              ToolResponse resp = WorkspaceContext.runWithFullAutonomy(
                  context.sessionId(), context.userId(), context.chatId(),
                  context.agentRole(),
                  () -> invokeTool(call, context.sessionId()));
              completedPre.add(call.withResult(resp.text(), resp.success()));
              preResults.put(call.toolName(),
                  objectMapper.writeValueAsString(resp));
          } catch (Exception e) {
              log.warnf("[PRE-EXEC] Tool %s failed pre-execution: %s",
                  call.toolName(), e.getMessage());
              completedPre.add(call.withResult(
                  "Pre-execution failed: " + e.getMessage(), false));
              preResults.put(call.toolName(), "{}");
          }
      }

      // Rebuild compositeName with ONLY the HITL-pending tools §7.32
      String hitlCompositeName = "parallel:" + split.hitlPending().stream()
          .map(ParallelToolCall::toolName)
          .collect(Collectors.joining(","));

      String preResultsJson = objectMapper.writeValueAsString(preResults);

      return StepDecision.terminal(
          checkpointManager.persistBatchWithPreExecuted(
              context, hitlCompositeName, batchJson,
              split.hitlPending(), preResultsJson, history));
  }

------------
75-A4 — ApprovalCheckpointManager: persistBatchWithPreExecuted()
------------

File: ApprovalCheckpointManager.java

ADD method:

  public AgentResult persistBatchWithPreExecuted(
          AgentExecutionContext context,
          String compositeName,
          String originalBatchJson,
          List<ParallelToolCall> hitlCalls,
          String preExecutedResultsJson,
          List<ReasoningStep> history) {

      // Same as persistBatch but also stores preExecutedResults
      // and seeds batchToolDecisions only for hitlCalls
      AgentCheckpoint cp = agentStore.load(context.sessionId(), context.agentRole())
          .orElseGet(() -> {
              AgentCheckpoint c = new AgentCheckpoint();
              c.setSessionId(context.sessionId());
              c.setAgentRole(context.agentRole());
              return c;
          });

      Map<String, String> decisions = new LinkedHashMap<>();
      hitlCalls.forEach(call ->
          decisions.put(call.toolName(), AgentSentinels.TOOL_DECISION_PENDING));

      String decisionsJson;
      try {
          decisionsJson = objectMapper.writeValueAsString(decisions);
      } catch (Exception e) {
          log.errorf("Failed to serialize decisions: %s", e.getMessage());
          return persist(context, compositeName, originalBatchJson, history);
      }

      cp.setStatus(CheckpointStatus.AWAITING_APPROVAL);
      cp.setPendingToolName(compositeName);
      cp.setPendingToolParametersJson(originalBatchJson);
      cp.setBatchToolDecisions(decisionsJson);
      cp.setPreExecutedResults(preExecutedResultsJson);   // §7.30
      cp.setOriginalGoal(context.goal());
      cp.setUpdatedAt(LocalDateTime.now());
      agentStore.save(cp);

      log.infof("[STATE-MACHINE] Pausing session %s — HITL: %s | pre-executed: %d tools",
          context.sessionId(), compositeName, hitlCalls.size());

      return AgentResult.awaitingApproval(
          compositeName, originalBatchJson, context.sessionId(),
          List.copyOf(history));
  }

------------
75-A5 — CheckpointResumeHandler: merge pre-executed results on resume
------------

File: CheckpointResumeHandler.java  resumeParallelBatch()

AFTER the decisions map is loaded and BEFORE the loop:

  // §7.30: Load pre-executed results and merge into completed list
  Map<String, String> preExecuted = Map.of();
  if (checkpoint.hasPreExecutedResults()) {
      try {
          preExecuted = objectMapper.readValue(
              checkpoint.getPreExecutedResults(),
              new TypeReference<Map<String, String>>() {});
      } catch (Exception e) {
          log.warnf("[PARALLEL-HITL-RESUME] session=%s failed to load pre-executed results.", sessionId);
      }
  }

INSIDE the loop, BEFORE invoking the tool:

  // §7.30: Use pre-stored result if available — never re-execute
  if (preExecuted.containsKey(call.toolName())) {
      try {
          ToolResponse stored = objectMapper.readValue(
              preExecuted.get(call.toolName()), ToolResponse.class);
          completed.add(call.withResult(stored.text(), stored.success()));
          continue;
      } catch (Exception e) {
          log.warnf("[PRE-EXEC-RESTORE] Could not restore result for %s, re-executing",
              call.toolName());
          // fall through to normal execution
      }
  }


=====
PHASE 75-B — Approval Delegation
=====

------------
75-B1 — Config + DelegateApprovalGuard
------------

application.yaml (all profiles):
  arcclaw:
    hitl:
      delegate-approver-ids: ""  # comma-separated Telegram user IDs

New file: arcclaw-core/.../reflex/DelegateApprovalGuard.java

  /**
   * Checks if a userId is a configured delegation admin. §7.33
   */
  @ApplicationScoped
  public class DelegateApprovalGuard {

      @ConfigProperty(name = "arcclaw.hitl.delegate-approver-ids",
                      defaultValue = "")
      Optional<List<String>> delegateApproverIds;

      public boolean isDelegate(String userId) {
          return delegateApproverIds
              .map(ids -> ids.contains(userId))
              .orElse(false);
      }
  }

------------
75-B2 — ChannelCommand.Approve / Deny: support sessionId targeting
------------

File: ChannelCommand.java

The existing `cronId` field on Approve/Deny detects a hyphen (`-`) to
distinguish cron IDs from tool names. Session IDs in ArcClaw are also
hyphenated (e.g. `"12345-1711234567890"`). Add a `targetSessionId`
field using a distinct format: session IDs are always numeric-hyphen-numeric.

ADD to ChannelCommand parsing:

  private static Optional<String> parseTargetSessionId(Optional<String> argOpt) {
      // Session IDs match pattern: digits-digits (e.g. 12345-1711234567890)
      return argOpt.filter(arg -> arg.matches("\\d+-\\d+"));
  }

UPDATE Approve and Deny records:
  record Approve(Optional<String> cronId,
                 Optional<String> toolName,
                 Optional<String> targetSessionId) implements ChannelCommand {}

  record Deny(Optional<String> cronId,
              Optional<String> toolName,
              Optional<String> targetSessionId) implements ChannelCommand {}

UPDATE parse() for approve/deny:
  case "approve" -> new Approve(
      parseCronId(optArg),
      parseToolName(optArg),
      parseTargetSessionId(optArg))
  case "deny" -> new Deny(
      parseCronId(optArg),
      parseToolName(optArg),
      parseTargetSessionId(optArg))

------------
75-B3 — ApproveReflex + DenyReflex: delegation path
------------

Files: ApproveReflex.java, DenyReflex.java

Inject in both:
  @Inject DelegateApprovalGuard delegateGuard;
  @Inject AuditService auditService;  // already available

In the execute() method, BEFORE the existing session-checkpoint lookup,
add a delegation branch:

  // §7.33: Delegation path — admin approving for another session
  if (approve.targetSessionId().isPresent()
          && delegateGuard.isDelegate(userId)) {
      String delegateSessionId = approve.targetSessionId().get();
      Optional<String> delegateToolName = approve.toolName();
      return handleDelegateApproval(
          delegateSessionId, delegateToolName, userId, chatId, context);
  }

  // §7.33: If a session ID is given but user is NOT a delegate — reject
  if (approve.targetSessionId().isPresent()
          && !delegateGuard.isDelegate(userId)) {
      return "⛔ You are not authorised to approve sessions for other users.";
  }

ADD handleDelegateApproval() in both reflexes:

  private String handleDelegateApproval(
          String delegateSessionId,
          Optional<String> toolName,
          String adminUserId,
          String chatId,
          MessageContext context) throws Exception {

      // Find the checkpoint for the delegated session
      String activeRole = null;
      for (AgentRoleEnum role : AgentRoleEnum.values()) {
          Optional<AgentCheckpoint> cpOpt =
              statefulAgentStore.load(delegateSessionId, role.toScopedName());
          if (cpOpt.isPresent()
                  && cpOpt.get().getStatus() == CheckpointStatus.AWAITING_APPROVAL) {
              activeRole = role.toScopedName();
              break;
          }
      }

      if (activeRole == null) {
          return String.format(
              "No active approval pending for session `%s`.", delegateSessionId);
      }

      // Audit log the delegation §7.33
      auditService.logSecurityEvent(
          SecurityEventType.DELEGATE_APPROVAL,
          adminUserId,
          "Admin %s approved session %s tool=%s".formatted(
              adminUserId, delegateSessionId, toolName.orElse("ALL")));

      if (toolName.isPresent()) {
          statefulAgentStore.updateBatchToolDecision(
              delegateSessionId, activeRole,
              toolName.get(), AgentSentinels.TOOL_DECISION_APPROVED);

          if (statefulAgentStore.isAllBatchToolsDecided(delegateSessionId, activeRole)) {
              return triggerBatchResume(
                  delegateSessionId, adminUserId, chatId, activeRole, context);
          }
          AgentCheckpoint cp =
              statefulAgentStore.load(delegateSessionId, activeRole).orElseThrow();
          List<String> stillPending =
              BatchDecisionUtil.pendingToolNames(cp, objectMapper);
          return String.format(
              "✅ [Delegate] Approved `%s` for session `%s`. Still pending: %s",
              toolName.get(), delegateSessionId,
              String.join(", ", stillPending));
      }

      // Approve all
      autoApproveAllPending(delegateSessionId, activeRole);
      return triggerBatchResume(
          delegateSessionId, adminUserId, chatId, activeRole, context);
  }

NOTE: DenyReflex gets the same method with TOOL_DECISION_DENIED and
      autoDenyAllPending(). The pattern is identical.

------------
75-B4 — AuditService: new SecurityEventType
------------

File: SecurityEventType.java (enum)

ADD:
  DELEGATE_APPROVAL   // Admin approved on behalf of another user. §7.33


=====
PHASE 75 EXECUTION ORDER
=====

  Step 0    §7.30–§7.33 in Architecture_Invariants.md

  75-H1     Re-indent handleCronApproval/Denial try blocks
  75-H2     Remove dead @Mock Logger from edit message test

  75-A1     AgentCheckpoint.preExecutedResults column + config
  75-A2     PreExecutionFilter utility (pure, no deps)
  75-A3     ParallelToolStepExecutor calls handleHitlBatch()
  75-A4     ApprovalCheckpointManager.persistBatchWithPreExecuted()
  75-A5     CheckpointResumeHandler merges pre-executed on resume

  75-B1     DelegateApprovalGuard + config
  75-B2     ChannelCommand.Approve/Deny + parseTargetSessionId()
  75-B3     ApproveReflex + DenyReflex delegation path
  75-B4     SecurityEventType.DELEGATE_APPROVAL

  mvn clean verify
  Assert: 892+ tests green.


=====
PHASE 75 TEST MATRIX
=====

  PreExecutionFilterTest (new, pure unit):
    - All FULL_AUTONOMY → all pre-executable, none HITL
    - Empty configuredSafeTools + SUPERVISED → all HITL
    - Mixed: one safe tool configured → 1 pre, rest HITL
    - Empty calls list → empty split

  ParallelToolStepExecutorPreExecTest (new):
    - Batch with 1 pre-executable + 1 HITL: pre-executed tool result
      stored, compositeName contains only the HITL tool name
    - Pre-execution failure: tool stored as failed, still proceeds
      to HITL pause for the remaining tools

  CheckpointResumeHandlerPreExecTest (new):
    - Resume with preExecutedResults populated: stored result used,
      invokeTool() NOT called for pre-executed tools
    - Resume with no preExecutedResults: behaves identically to P73

  DelegateApprovalGuardTest (new, pure unit):
    - isDelegate() true for configured ID
    - isDelegate() false for unconfigured ID
    - isDelegate() false when config is empty

  ApproveReflexDelegateTest (new):
    - Non-delegate user with sessionId arg → rejection message
    - Delegate user, per-tool: correct decision stored, audit logged
    - Delegate user, approve-all: all tools approved, resume triggered
    - Delegate user, unknown sessionId: "No active approval" message

  ChannelCommandParseTest (updated):
    - "/approve 12345-1711234567890" → targetSessionId present,
      cronId empty, toolName empty
    - "/approve 12345-1711234567890 github" → targetSessionId +
      toolName both present


=====
END OF REFACTORING & ENHANCING SEGMENT
=====

  Phases P67–P75 are complete. The parallel batch HITL system is:
  ✅ Fully implemented (P67–P72)
  ✅ Telegram keyboard UX (P73–P74)
  ✅ Pre-execution optimisation (P75-A)
  ✅ Approval delegation (P75-B)
  ✅ Race guard, stale-approval GC, MarkdownV2 (P73–P74)

  Next segment: observability, metrics, and load testing.