## Tier 7 – Future Decomposition Observations

> Large classes NOT part of C2 scope. Flagged for future decomposition consideration. No invariant violation – these are quality observations.

| File | Lines | Module Path | Decomposition Notes |
| :--- | :--- | :--- | :--- |
| `AppBootstrap.java` | 1043 | `arcclaw-app/.../config/` | Split config registration from startup orchestration |
| `AgentSessionService.java` | 579 | `arcclaw-core/.../api/` | Split into SessionLifecycleService + SessionQueryService + ApprovalService |
| `CheckpointResumeHandler.java` | 518 | `arcclaw-core/.../agent/loop/` | Extract resume strategies per checkpoint type |
| `SandboxExecTool.java` | 482 | `arcclaw-core/.../tools/` | Size driven by sandbox orchestration; monitor but tolerable |
| `ToolStepHandler.java` | 464 | `arcclaw-core/.../agent/loop/` | Extract parallel vs sequential tool execution paths |
| `LangChainReasoningEngine.java` | 458 | `arcclaw-core/.../agent/reasoning/` | Addressed by T1-1/T1-2 (LLM port extraction) |
| `CliUtils.java` | 447 | `arcclaw-app/.../config/` | App-layer utility; not core orchestration |
| `SystemPromptBuilder.java` | 442 | `arcclaw-core/.../agent/reasoning/` | Extract per-section prompt builders |
| `ApproveReflex.java` | 440 | `arcclaw-core/.../reflex/` | Large for a reflex; extract batch vs single-tool approval paths |
| `EngineStepHandler.java` | 388 | `arcclaw-core/.../agent/loop/` | Near threshold; monitor |

---
