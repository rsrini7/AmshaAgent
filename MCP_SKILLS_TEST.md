## Test Skills

| # | What to run | What to check |
|---|---|---|
| 1 | `SKILLS-1` in `11_skill.http` | Your two existing DB-tier skills are listed |
| 2 | `SKILLS-2/3` | Upsert both, response is `{"message":"Skill saved and registry reloaded: ..."}` |
| 3 | Telegram: `"review my code for security issues"` | See `⏳` then code-reviewer response. Logs show `matched skill:code-reviewer` — **no Groq call** |
| 4 | Telegram: `"how does the sort algorithm work"` | Researcher persona. Logs show `matched skill:researcher` |
| 5 | `SKILLS-4` | Upsert again — confirm it's still 201, not a duplicate |
| 6 | `SKILLS-5/6` | Force refresh, verify sources say `database` |
| 7 | `SKILLS-7/8` | Delete researcher, re-list to confirm it's gone |
| 8 | Telegram: `"how does the sort algorithm work"` again | Now routes through Groq LLM — you'll see the classifier log instead |
| 9 | `SKILLS-9 → 13` | Error cases — 400, warn+skip, 404, 401 |

---

## Test MCP server (inbound)

| # | What to run | What to check |
|---|---|---|
| 1 | `MCP-ADMIN-1` | `serverEnabled:true` — if 404, check your `.env` |
| 2 | `MCP-ADMIN-2` | `exposedInternalTools` has github + memory_search, `registeredDynamicTools` is empty |
| 3 | `MCP-1` | 200, IntelliJ captures session ID into `{{mcpSessionId}}` |
| 4 | `MCP-2` | 202 — handshake complete |
| 5 | `MCP-3` | Both tools appear in tools array |
| 6 | `MCP-4` | memory_search returns content block, audit log shows `MCP_TOOL_INVOKED` |
| 7 | `MCP-5` | github returns content (or error string if GH_TOKEN not seeded — still 200) |
| 8 | Telegram: `/mcp` | McpReflex shows `Exposed internal tools (2): github, memory_search` and `Dynamic MCP tools: none connected` |
| 9 | `MCP-ERR-1/2` | JSON-RPC error body for disallowed tool, 4xx for missing session |

---

## Test MCP client (outbound)

This is the self-connect smoke test — no external server needed:

| # | What to run | What to check |
|---|---|---|
| 1 | `MCP-CLIENT-1` | 200, `status:connected`, `toolsRegistered:2` — this proves the full outbound stack end to end |
| 2 | `MCP-ADMIN-2` | `registeredDynamicTools` now has 2 entries, both with `mcpServerName:"self"` and `transport:"HTTP_SSE"` |
| 3 | `MCP-CLIENT-3` | Confirms server listing |
| 4 | Telegram: `/mcp` | Now shows `Dynamic MCP tools (2): github [self, HTTP_SSE] ...` |
| 5 | `MCP-CLIENT-5` | Disconnect, 200 with `toolsRemoved:2` |
| 6 | `MCP-CLIENT-6` | `registeredDynamicTools` is empty again |
| 7 | Telegram: `/mcp` | Back to `Dynamic MCP tools: none connected` |
| 8 | `MCP-CLIENT-ERR-1/2/3/4` | 400, 502, 404, 401 |

For a real external server, uncomment and fill in `MCP-CLIENT-4` with a live URL. Once connected, just chat normally in Telegram — the LLM invokes dynamic tools exactly like built-in ones via `ExternalMcpToolAdapter`.