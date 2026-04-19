## Tier 6 – Code Quality & Sealed Types (Wave 4)

### T6-1: Typed UI/Backend Contract DTOs (BV2)
* **Problem**: UI consumes raw JSON from REST endpoints. No shared TypeScript type definitions generated from Java DTOs.
* **Done When**: OpenAPI spec generates TypeScript types consumed by `arcclaw-ui`.

---

### T6-2: ToolExecutionContext Argument (BV3)
* **Problem**: Tools access session context via static `WorkspaceContext` accessors. Explicit `ToolExecutionContext` parameter would improve testability.
* **Invariants Referenced**: §2.3
* **Done When**: `AgentTool.execute()` receives `ToolExecutionContext` parameter.

---

### T6-3: Sealed ToolResponse Hierarchy (IA1)
* **Problem**: `ToolResponse` is a flat record with `success` boolean. Typed success/failure variants would enable pattern matching.
* **Done When**: `ToolResponse` is sealed with `ToolSuccess` and `ToolFailure` subtypes.

---

### T6-4: Sealed ReasoningStep Hierarchy (IA2)
* **Problem**: `ReasoningStep` uses string `action` field for dispatch. A sealed hierarchy would enable exhaustive pattern matching.
* **Invariants Referenced**: §7.9
* **Done When**: `ReasoningStep` is sealed with typed variants per action.

---

### T6-5: Split MessageContext (IA3)
* **Problem**: `MessageContext` mixes domain concerns (session, user) with transport concerns (channel, metadata).
* **Done When**: Separate `DomainContext` and `TransportContext` types.

---

### T6-6: Tool SPI Lifecycle Hooks (IM3)
* **Problem**: Tools have no lifecycle hooks (init, destroy, health check). No way to validate tool health at startup.
* **Invariants Referenced**: §4.5
* **Done When**: `AgentTool` has optional `onInit()`, `onDestroy()`, `healthCheck()` default methods.

---

### T6-7: SSE Adaptive Backoff (M2)
* **Problem**: SSE reconnection uses fixed intervals.
* **Done When**: Exponential backoff with jitter on SSE reconnect.

---

### T6-8: UI Message List Virtualization (M3)
* **Problem**: Long chat histories render all DOM nodes, causing performance degradation.
* **Invariants Referenced**: §21.1
* **Done When**: Virtual scrolling for message list; only visible messages in DOM.

---

### T6-9: Clarify Reflex vs Command Terminology (NI2)
* **Problem**: Codebase uses "reflex" and "command" inconsistently.
* **Done When**: Consistent terminology across code, docs, and UI.

---

### T6-10: HITL Typed Approval Frames (C4)
* **Problem**: HITL approval messages use stringly-typed text patterns. UI and backend are coupled to approval phrase text.
* **Done When**: Typed `ApprovalFrame` record replaces string-based approval detection.