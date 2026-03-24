## ScopedValue Binding Rules — WorkspaceContext vs WorkspaceScopeHolder

### The Two Binding Layers

| ScopedValue | Bound by | Read via | Use for |
|---|---|---|---|
| `WorkspaceContext.USER_ID` | `WorkspaceContext.run()` | `WorkspaceContext.userId()` | Tool execution, agent logic, business code |
| `WorkspaceScopeHolder.USER_ID` | `WorkspaceScopeHolder.call()` | `WorkspaceScopeHolder.USER_ID.get()` | Reflex commands, CDI `@WorkspaceScoped` bean activation |

### Rule 1 — Tools always use `WorkspaceContext`

Any class called from `AgentDispatchHandler` → `AgentLoop` → `ToolRegistry` → `tool.execute()` is inside a `WorkspaceContext.run()` call. Always read context via:

```java
WorkspaceContext.userId()      // never WorkspaceScopeHolder.USER_ID.get()
WorkspaceContext.chatId()      // never WorkspaceScopeHolder.CHAT_ID.get()
WorkspaceContext.requireSessionId()
WorkspaceContext.autonomy()
WorkspaceContext.agentRole()
```

### Rule 2 — Reflexes use `WorkspaceScopeHolder`

Reflex commands go through `CommandDispatchHandler` which calls `WorkspaceScopeHolder.call()` directly — NOT `WorkspaceContext.run()`. So reflexes correctly use:

```java
WorkspaceScopeHolder.USER_ID.isBound()
WorkspaceScopeHolder.USER_ID.get()
```

### Rule 3 — Audit drain is the only legitimate fallback

`ToolRegistry.resolveUserId()` is the **only** place that legitimately falls back from `WorkspaceScopeHolder` — because the audit telemetry drain runs on a separate virtual thread where neither context is bound:

```java
// ONLY legitimate use of WorkspaceScopeHolder in tool path
private String resolveUserId() {
    return WorkspaceScopeHolder.USER_ID.isBound()
            ? WorkspaceScopeHolder.USER_ID.get()
            : "internal";
}
```

### Rule 4 — Tests must mirror production binding

| Production path | Test must use |
|---|---|
| Tool executed via agent loop | `WorkspaceContext.run(...)` |
| Reflex executed via command dispatch | `WorkspaceScopeHolder.call(...)` |
| Unbound (403 test) | `WorkspaceContext.run("session", "", ...)` — blank userId, not unbound |

### The Diagnostic

If you see `tenant context not bound` or `SESSION_ID is unbound` in a tool, run:

```bash
rg "WorkspaceScopeHolder\.(USER_ID|CHAT_ID|SESSION_ID)\.get" --type java -n
```

Any hit inside a `Tool` class is wrong — replace with `WorkspaceContext.*()` accessor.

