## Tier 8 – UI Refactoring & Missing Features (Wave 5)

> Can run in **parallel** with backend Waves 3c-4. Governed by §21 (UI Architecture Invariants).

### ### UI Architecture (UW1-UW8)

| ID | Fix | Invariant |
| :--- | :--- | :--- |
| UW1 | State management via `@lit/context` | §21.2 |
| UW2 | Router library (Vaadin Router) | §21.1 |
| UW3 | WebSocket reconnection (overlaps T5-7) | §21.8 |
| UW4 | SSE adaptive backoff (overlaps T6-7) | §21.8 |
| UW5 | Optimistic UI updates | §21.4 |
| UW6 | Message list virtualization (overlaps T6-8) | §21.1 |
| UW7 | Service worker for offline support | §21.1 |
| UW8 | Accessibility (ARIA labels, live regions, keyboard nav) | §21.7 |

### ### UI Performance (UP1-UP3)

| ID | Fix |
| :--- | :--- |
| UP1 | Cache rendered markdown per message ID |
| UP2 | Debounce textarea input |
| UP3 | Paginated history API |

### ### Missing Features (MF1-MF10)

| ID | Fix |
| :--- | :--- |
| MF1 | Approval notification badge |
| MF2 | Multi-session tabs |
| MF3 | Message search |
| MF4 | Export/download transcript |
| MF5 | Typed progress indicators (Thinking / Calling tools / Waiting) |
| MF6 | Structured tool result rendering |
| MF7 | Skill browser panel |
| MF8 | Session analytics/stats |
| MF9 | Theme toggle wiring |
| MF10 | Error boundary / crash recovery |

**Acceptance**: Lighthouse >90, WCAG 2.1 AA audit pass, each feature behind flag.