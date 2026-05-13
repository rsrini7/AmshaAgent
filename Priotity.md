## **Executive Summary – What Remains**

| Priority Band | Items | Effort | Impact |
| :--- | :--- | :--- | :--- |
| **P1 – Quick Wins** (< 1 hour each) | 6 | ~5 hours | Code hygiene |
| **P2 – Small Refactorings** (1-4 hours each) | 11 | ~25 hours | Resilience + tests |
| **P3 – Medium Decompositions** (4-16 hours each) | 9 | ~70 hours | Checkstyle compliance |
| **P4 – Architecture + Ops** (1-3 days each) | 8 | ~80 hours | Production readiness |
| **P5 – UI & Feature** (varies) | 5 | ~30 hours | UX + maintainability |
| **\*\*Total\*\*** | **\*\*39 items\*\*** | **\*\*~210 hours\*\*** | |

---

## **Test Coverage by Module (Verified 2026-05-05)**

| Module | Main | Test | Ratio | Status | Action |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `arcclaw-app` | 59 | 51 | 86% | ✅ Good | - |
| `arcclaw-core` | 354 | 167 | 47% | ⚠️ Acceptable | Focus SR-2 on this |
| `arcclaw-channel-telegram` | 14 | 14 | 100% | ✅ Excellent | - |
| `arcclaw-channel-web` | 10 | 6 | 60% | ⚠️ Acceptable | - |
| `arcclaw-persistence-sqlite`| 28 | 19 | 68% | ✅ Good | - |
| `arcclaw-mcp` | 20 | 10 | 50% | ⚠️ Needs work | - |
| `arcclaw-rag` | 11 | 3 | **27%** | 🔴 | SR-5 |
| `arcclaw-channel-api` | 27 | 6 | **22%** | 🔴 | SR-6 |
| `arcclaw-persistence-api` | 25 | 1 | **4%** | 🔴 | SR-3 |
| `arcclaw-persistence-postgres`| 4 | 0 | **0%** | 🔴 | SR-4 |
| `arcclaw-onboarding` | 19 | 7 | 37% | ⚠️ | - |
| `arcclaw-deployment` | 6 | 3 | 50% | ⚠️ | - |
| `arcclaw-tool-playwright` | 5 | 2 | 40% | ⚠️ | - |

---

## **Thread.sleep Remaining (8 locations)**

| File | Line | Context | Fix Priority |
| :--- | :--- | :--- | :--- |
| `AgentCronScheduler.java` | 114 | Startup delay | QW-3 |
| `EngineStepHandler.java` | 229 | Rate-limit retry | QW-4 |
| `ResumeStrategyDispatcher.java` | 174 | Rate-limit +100ms padding | LOW (short) |
| `AsyncPersistenceEngine.java` | 179 | Queue drain retry | LOW (BackoffRetryExecutor wraps) |
| `CompactionService.java` | 312 | Exponential backoff | LOW (already via BackoffRetryExecutor) |
| `BackoffRetryExecutor.java` | 74 | The retry utility itself | N/A (intentional) |
| `PlaywrightPageRunner.java` | 245,247 | Browser wait | LOW (external process sync) |

***

## **Execution Roadmap (Recommended Order)**

```text
Sprint 1 - Quick Wins + Critical Ops (Week 1)
├── QW-1   Prometheus alert rules
├── QW-2   Silent catch block logging
├── QW-3   AgentCronScheduler sleep -> config/Uni
├── QW-4   EngineStepHandler sleep -> BackoffRetryExecutor
├── QW-5   Complete @ToolMetadata (2 remaining tools)
├── QW-6   Add @Size validation on DTOs
├── SR-8   WsTokenStore atomic fix
├── SR-11  @CircuitBreaker on MCP invoke()
└── AO-5   Idempotency on approvals

Sprint 2 - Resilience + Tests (Week 2-3)
├── SR-1   SSE exponential backoff
├── SR-2   Top-20 broad catch(Exception) replacement
├── SR-3   arcclaw-persistence-api tests
├── SR-4   arcclaw-persistence-postgres tests
├── SR-5   arcclaw-rag tests
├── SR-6   arcclaw-channel-api tests
├── SR-7   RAG silent exception fix
├── SR-9   Externalize remaining magic numbers
├── SR-10  MCP transport health checks
└── AO-4   MDC correlation ID propagation

Sprint 3 - Decompositions (Week 3-4)
├── MD-1   ToolStepHandler (486 -> <400)
├── MD-2   SandboxExecTool (485 -> <400)
├── MD-3   FinishActionHandler (480 -> <400)
├── MD-4   PostgresCheckpointStoreAdapter (479 -> 2 classes)
├── MD-5   AbstractDecisionReflex (460 -> 2 classes)
├── MD-6   AgentLoop (441 -> <400)
├── MD-7   ParallelToolStepExecutor (423 -> <400)
├── MD-8   McpClientService (409 -> 2-3 classes)
└── MD-9   AgentSessionService (406 -> <400)

Sprint 4 - Architecture & UI (Week 5-6)
├── AO-1   Wire OpenAPI generated types into UI
├── AO-2   Pagination on query endpoints
├── AO-3   Rate limiting
├── AO-6   WebSocket state consolidation
├── AO-7   API versioning
├── AO-8   MessageContext split
├── UW-1   Router library
├── UW-2   arc-onboarding.ts decomposition
├── UW-3   arc-chat-interface.ts decomposition
└── UW-5   UI SSE backoff
```

***

## **Summary of Sealed Type Adoption (12 hierarchies — excellent)**

| Sealed Interface | Permits | Module |
| :--- | :--- | :--- |
| `ToolResponse` | ToolSuccess, ToolFailure, ToolPending | core |
| `ReasoningStep` | ToolCallStep, ObservationStep, FinishStep, ParallelStep | core |
| `AgentResult` | Success, Failure, AwaitingApproval, ... | core |
| `AgentState` | Running, Paused, Completed, Failed | core |
| `FinishOutcome` | Completed, RetryLeak, Correction, SalvageFailed | core |
| `GuardDecision` | Allow, Deduplicate, PauseForApproval, Blocked | core |
| `ResumeDecision` | Resume, Skip, Abort | core |
| `InvokeResult` | (nested in ToolStepHandler) | core |
| `ChannelMessage` | TelegramMessage, WebMessage, ... | channel-api |
| `ChannelCommand` | SendText, SendMarkdown, ... | channel-api |
| `ReplyNode` | Text, Keyboard, ... | channel-api |
| `BatchCallbackEvent` | (nested) | channel-telegram |

---

## **What's NOT Pending (Explicitly Excluded)**

| Item | Reason |
| :--- | :--- |
| Lit Virtualizer (SR-13) | Reverted — caused alignment issues |
| CliUtils.java (468 lines) | CLI utility, no SRP violation |
| AgentCheckpoint.java (449 lines) | Entity with fields/accessors, tolerable |
| WorkspaceContext.java (363 lines) | Under gate; @Deprecated methods being phased out |
| SR-5/SR-6 Onboarding/Deployment tests | Improved to 37%/50% — acceptable threshold |
| ProviderFallbackRouter | Not needed — routing handled by LangChainReasoningEngine directly |
| Dead letter queue | Feature scope — not a refactoring item |