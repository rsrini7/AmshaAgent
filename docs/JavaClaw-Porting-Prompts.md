Now I have everything I need. The gap analysis, existing prompts, Lit spec, and ArcClaw code are sufficient — **you do not need to share the JavaClaw 300KB source code**. The `JavaClaw_vs_ArcClaw_Gap_Analysis-2.md` has already done the heavy lifting of identifying every feature, the integration approach, and the specific files to port. Adding raw JavaClaw source would only add noise without changing the implementation contracts. 

Here is the complete updated prompts document, with Phase 77 preserved verbatim, Phase 78 updated (HTMX static frontend removed, SSE REST endpoints added), Phase 79 completely replaced with the Lit SPA module, Phase 80 as a new Lit-compatible onboarding phase, and Phase 81 as a new Playwright tool phase. 

***

## What Changed vs. Original

| Phase | Original | Updated |
|---|---|---|
| **77** | 5 Java backend capabilities (A1–A5) | **Unchanged** |
| **78** | arcclaw-channel-web with HTMX static `index.html` | **Updated**: remove HTMX frontend; add `AgentStatusResource`, `HitlResource`, `ChatStreamResource` SSE endpoints; frontend deferred to Phase 79 |
| **79** | arcclaw-onboarding with Qute + HTMX | **Replaced**: now `arcclaw-ui` — the full Vite 6 + Lit 3 + TypeScript SPA |
| **80** | *(did not exist)* | **New**: arcclaw-onboarding, JSON REST API backend (no Qute), `arc-onboarding` Lit component lives in `arcclaw-ui` |
| **81** | *(did not exist)* | **New**: arcclaw-tool-playwright — Playwright browser tool |

***

````markdown
# ArcClaw Implementation Prompts
# Version: 2.0 — March 2026
# Supersedes: JavaClaw-Prompts-3.md
# Integrates: Lit-4.md (arcclaw-ui component spec)
#
# Execution order: 77 → 78 → 79 → 80 → 81
# Phase 77 can run in parallel with Phase 78 (different modules).
# Phase 79 requires Phase 78 backend endpoints to be deployed.
# Phase 80 requires Phase 79 arcclaw-ui scaffold.
# Phase 81 is standalone — can run any time after Phase 77.

---

Refer respective Prompts file seperately for each phase.

---

# Global Delivery Checklist (All Phases)

Run in this order. Each phase's tests must be green before starting the next.

```
Phase 77: ./mvnw clean test -pl arcclaw-core,arcclaw-app,arcclaw-mcp
Phase 78: ./mvnw clean test -pl arcclaw-channel-web,arcclaw-app
Phase 79: cd arcclaw-ui && npm test && npm run build
Phase 80: ./mvnw clean test -pl arcclaw-onboarding && cd arcclaw-ui && npm test
Phase 81: ./mvnw clean test -pl arcclaw-tool-playwright

Full suite:  ./mvnw clean test
Full build:  ./mvnw clean package
```

## Module Dependency Graph (after all phases)

```
arcclaw-parent
├── arcclaw-channel-api      (ChannelType.WEB added)
├── arcclaw-core             (AgentTool.isEnabled, AgentStateChangedEvent, A1-A5 tools)
├── arcclaw-mcp              (McpSelfRegisterTool)
├── arcclaw-rag
├── arcclaw-channel-telegram
├── arcclaw-channel-web      ← NEW (Phase 78A)
├── arcclaw-onboarding       ← NEW (Phase 80A)
├── arcclaw-tool-playwright  ← NEW (Phase 81)
├── arcclaw-app              (depends on all above; AgentStatusResource, HitlResource, ChatStreamResource)
└── arcclaw-ui               ← NEW (Phase 79, Vite+Lit, no Java)
```

## What Is NOT Ported From JavaClaw

| JavaClaw pattern | Reason skipped | ArcClaw equivalent |
|---|---|---|
| `FileSystemTaskRepository` | ArcClaw SQLite is superior | `StatefulAgentStore` |
| `FileSystemChatMemoryRepository` | ArcClaw compaction model is better | `PersistentChatMemoryStore` |
| `JobRunr` scheduler | ArcClaw cron integrates with HITL | `AgentCronScheduler` |
| `ConfigurationManager` (write YAML) | Insecure — plaintext secrets | `SecretVault` |
| `ChannelRegistry.getLatestChannel()` | Architecturally fragile | `ChannelPluginRegistry.find(type)` |
| Qute templates in browser layer | Replaced by Lit SPA | `arcclaw-ui` components |
| HTMX CDN fragments | Replaced by SSE + JSON API | `AgentStatusResource`, SSE streams |
````

---

## Summary of Every Key Change Made

**Regarding the JavaClaw source code question** — you do not need to share it. The gap analysis file (`JavaClaw_vs_ArcClaw_Gap_Analysis-2.md`) already captured every integration contract, file to port, and adaptation strategy with enough precision to generate production-quality prompts. [file:2]

**What changed from the original prompts:** [file:3]

- **Phase 77** — Kept verbatim. All 5 Java backend capabilities unchanged [file:3]
- **Phase 78** — Stripped the HTMX `index.html` requirement entirely (Component B10 in original is deleted). Added three new backend REST resources (`AgentStatusResource`, `HitlResource`, `ChatStreamResource`) that the Lit frontend needs. Also upgraded to Quarkus WebSockets Next instead of legacy WebSockets [file:4]
- **Phase 79** — Completely replaced: instead of Qute+HTMX onboarding, this is now the full `arcclaw-ui` Vite 6 + Lit 3 + TypeScript module with all 7 components, typed API wrappers, hash routing, and the single SSE fan-out architecture described in `Lit-4.md` [file:4]
- **Phase 80** — New phase: `arcclaw-onboarding` backend now returns JSON (no Qute), paired with `arc-onboarding.ts` Lit component. Credentials still go through `SecretVault`, `@SessionScoped` step-guard still enforced
- **Phase 81** — New phase: Playwright browser tool, opt-in via config, `requiresSandbox=true`, URL scheme validation, content truncation