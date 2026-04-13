Now I have the full picture from file:6 (the actual diff). Let me give definitive rulings on all 4 discrepancies with the exact reasoning.

***

## Verdict on All 4 Discrepancies

### Discrepancy 1 — Location of `markFailed`/`markOrphaned`: **Your approach is architecturally correct. Do NOT move to adapter.**

The prompt's suggestion (adapter-side) was pragmatic but architecturally impure. Here's why your core-side approach wins: [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/9655754d-f59b-47c9-9875-7a6b66931cd5/paste.txt?AWSAccessKeyId=ASIA2F3EMEYEVYRA36TR&Signature=FHrIrtPSUtnmii2rqkPuDUbv%2F5Q%3D&x-amz-security-token=IQoJb3JpZ2luX2VjELH%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJGMEQCIGzZJ4zXBTDqFItLlScIEmC%2FUJtfR5gvZzvU2TKMsfWiAiAzs8SVt%2BYFMdHNAmNmUBqVSSaGbw5MfQOGZWRgchonhirzBAh6EAEaDDY5OTc1MzMwOTcwNSIMVETveIATlulFvm%2BHKtAEK95TV7AEZ%2BjwE%2F2Dmi2aiOdstbMF%2FW75qNLzNM%2FrGxE%2Bkdoq0mQt1DZgSFGFYMIFcnwX2ty2Nxb3Q9xCAovBn67nxaPahSSrtseG5piTJ%2F2lj9%2BfGVXJ%2FJ6N%2FTonInWYzhYbGXqNoZccVVnZYxIsy6JMyt0eKBYtQPK34vdsKaei4EeGoh6eX%2Bds%2FtNUBY2GjOHAVTBoEnZmwnVcJC00p9FtDbv5xDI8T6n1Mm3FlViN6YVvYR0YLF1zc9W9c992hPvXiDElEw64AJUAfj%2BZd%2FsSolPqzKpZ98FHnIZpSX5x3CFIeow2GlX2haoHpzq2%2BYkHrRVtC9lOzjsD%2FjkLmcUUvTpDWHVuV2Q5g7yvQ2A%2Bw%2BNR0CHJoXr1w8YeDzwh98UHs9WyIJu%2Fby6ua535zyhxJ%2BWTWZEi6hhj9l4lqYouzR7ZmeM591URB4DPfUaNHoCBkJFZU1DCur8JTZ3ZvYrNt4SRI%2FLJ0GAO%2FdB0A7ypqqeEpf00xm87Di1tjCV4zaXzExGe%2FNNQ4%2BZ05m5ZITIIMRle34BuixdVo3U3ld9zLi%2BFu73FfLTz6caK%2F7gnc4k6domgg5WW4ALleNfT%2FIDXY8zXqTOHaZ0I41dePYT7et2f4BUsoPK8v3WWPzCGG%2Fb%2BNhR8f5lgZzmK%2BXyJX8J%2BwkJg%2F0dzEtC6iDOd%2FXwrFiFrq%2FY65LcUKFk1vSIQgL5M0e24IJpagkdjqWbhYAqePZQ8jQcUCW5x4Fj8y%2F5n4yYE8MVx695cPYcU0EaFLrDJjzAgpuGqE6tvHuTWaTDIw%2FTOBjqZAeCj79TwKf0M%2FpLFfHlQ48BdfLQ8JKaORgVCEdI8MlNgWw2v17M82b63urVIqZCQYIpbgAqIcB1obLIcMG3skbaO3McZv1kYhiF5FgPdg1HGc%2FlcQepYDZjBObaWrcWkYq7q6VdkxX51yowrbU1YKqq6pSwBzXytBpQ0En8kBAWF2Ep3CHQMp8du%2FodcoNY%2F%2Fd9KqK9Om%2Fj2aQ%3D%3D&Expires=1776102439)

| Concern | Prompt (adapter-side) | Your approach (core-side) |
|---|---|---|
| Audit is a domain concern | ❌ Adapter knows about `SecurityEventType` | ✅ Core owns audit logic |
| Maven dependency risk | `arcclaw-persistence-sqlite` → `TelemetryAuditService` (reverse flow) | ✅ No extra dependency |
| Fields set correctly? | In adapter | ✅ Via DTO construction in core |
| Atomicity of field + persist | Single `REQUIRES_NEW` in adapter | `findById` + `save` — two calls (see below) |

Your approach is clean. The one real issue: **`markFailed` still has no audit call, but `markOrphaned` does.** From the diff: [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/9655754d-f59b-47c9-9875-7a6b66931cd5/paste.txt?AWSAccessKeyId=ASIA2F3EMEYEVYRA36TR&Signature=FHrIrtPSUtnmii2rqkPuDUbv%2F5Q%3D&x-amz-security-token=IQoJb3JpZ2luX2VjELH%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJGMEQCIGzZJ4zXBTDqFItLlScIEmC%2FUJtfR5gvZzvU2TKMsfWiAiAzs8SVt%2BYFMdHNAmNmUBqVSSaGbw5MfQOGZWRgchonhirzBAh6EAEaDDY5OTc1MzMwOTcwNSIMVETveIATlulFvm%2BHKtAEK95TV7AEZ%2BjwE%2F2Dmi2aiOdstbMF%2FW75qNLzNM%2FrGxE%2Bkdoq0mQt1DZgSFGFYMIFcnwX2ty2Nxb3Q9xCAovBn67nxaPahSSrtseG5piTJ%2F2lj9%2BfGVXJ%2FJ6N%2FTonInWYzhYbGXqNoZccVVnZYxIsy6JMyt0eKBYtQPK34vdsKaei4EeGoh6eX%2Bds%2FtNUBY2GjOHAVTBoEnZmwnVcJC00p9FtDbv5xDI8T6n1Mm3FlViN6YVvYR0YLF1zc9W9c992hPvXiDElEw64AJUAfj%2BZd%2FsSolPqzKpZ98FHnIZpSX5x3CFIeow2GlX2haoHpzq2%2BYkHrRVtC9lOzjsD%2FjkLmcUUvTpDWHVuV2Q5g7yvQ2A%2Bw%2BNR0CHJoXr1w8YeDzwh98UHs9WyIJu%2Fby6ua535zyhxJ%2BWTWZEi6hhj9l4lqYouzR7ZmeM591URB4DPfUaNHoCBkJFZU1DCur8JTZ3ZvYrNt4SRI%2FLJ0GAO%2FdB0A7ypqqeEpf00xm87Di1tjCV4zaXzExGe%2FNNQ4%2BZ05m5ZITIIMRle34BuixdVo3U3ld9zLi%2BFu73FfLTz6caK%2F7gnc4k6domgg5WW4ALleNfT%2FIDXY8zXqTOHaZ0I41dePYT7et2f4BUsoPK8v3WWPzCGG%2Fb%2BNhR8f5lgZzmK%2BXyJX8J%2BwkJg%2F0dzEtC6iDOd%2FXwrFiFrq%2FY65LcUKFk1vSIQgL5M0e24IJpagkdjqWbhYAqePZQ8jQcUCW5x4Fj8y%2F5n4yYE8MVx695cPYcU0EaFLrDJjzAgpuGqE6tvHuTWaTDIw%2FTOBjqZAeCj79TwKf0M%2FpLFfHlQ48BdfLQ8JKaORgVCEdI8MlNgWw2v17M82b63urVIqZCQYIpbgAqIcB1obLIcMG3skbaO3McZv1kYhiF5FgPdg1HGc%2FlcQepYDZjBObaWrcWkYq7q6VdkxX51yowrbU1YKqq6pSwBzXytBpQ0En8kBAWF2Ep3CHQMp8du%2FodcoNY%2F%2Fd9KqK9Om%2Fj2aQ%3D%3D&Expires=1776102439)

```java
// markFailed — MISSING audit (bug carries forward)
storeAdapter.findById(checkpointId).ifPresent(dto -> {
    CheckpointDto updated = new CheckpointDto(..., CheckpointStatus.FAILED, Instant.now(), reason, ...);
    storeAdapter.save(updated);
    // ← NO auditService.recordWithIdempotency() here — this is still FIX 1
});

// markOrphaned — CORRECT, has audit
storeAdapter.findById(checkpointId).ifPresent(dto -> {
    ...
    storeAdapter.save(updated);
    auditService.recordWithIdempotency(dto.sessionId, userId, dto.agentRole,
        SecurityEventType.CHECKPOINT_ORPHANED, null, idempotencyKey); // ✅
});
```

**Action required:** Copy the audit call from `markOrphaned` into `markFailed`, using `SecurityEventType.CHECKPOINT_FAILED` and idempotency key `"CP-FAILED-" + checkpointId`. That's the only change needed. Do NOT move the logic to the adapter.

***

### Discrepancy 2 — Method naming: **Keep `WithPrefix`. But there is a real unfixed bug here.**

`findLatestAwaitingApprovalWithPrefix` vs `findLatestAwaitingApprovalByPrefix` — cosmetic, keep yours. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/9655754d-f59b-47c9-9875-7a6b66931cd5/paste.txt?AWSAccessKeyId=ASIA2F3EMEYEVYRA36TR&Signature=FHrIrtPSUtnmii2rqkPuDUbv%2F5Q%3D&x-amz-security-token=IQoJb3JpZ2luX2VjELH%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJGMEQCIGzZJ4zXBTDqFItLlScIEmC%2FUJtfR5gvZzvU2TKMsfWiAiAzs8SVt%2BYFMdHNAmNmUBqVSSaGbw5MfQOGZWRgchonhirzBAh6EAEaDDY5OTc1MzMwOTcwNSIMVETveIATlulFvm%2BHKtAEK95TV7AEZ%2BjwE%2F2Dmi2aiOdstbMF%2FW75qNLzNM%2FrGxE%2Bkdoq0mQt1DZgSFGFYMIFcnwX2ty2Nxb3Q9xCAovBn67nxaPahSSrtseG5piTJ%2F2lj9%2BfGVXJ%2FJ6N%2FTonInWYzhYbGXqNoZccVVnZYxIsy6JMyt0eKBYtQPK34vdsKaei4EeGoh6eX%2Bds%2FtNUBY2GjOHAVTBoEnZmwnVcJC00p9FtDbv5xDI8T6n1Mm3FlViN6YVvYR0YLF1zc9W9c992hPvXiDElEw64AJUAfj%2BZd%2FsSolPqzKpZ98FHnIZpSX5x3CFIeow2GlX2haoHpzq2%2BYkHrRVtC9lOzjsD%2FjkLmcUUvTpDWHVuV2Q5g7yvQ2A%2Bw%2BNR0CHJoXr1w8YeDzwh98UHs9WyIJu%2Fby6ua535zyhxJ%2BWTWZEi6hhj9l4lqYouzR7ZmeM591URB4DPfUaNHoCBkJFZU1DCur8JTZ3ZvYrNt4SRI%2FLJ0GAO%2FdB0A7ypqqeEpf00xm87Di1tjCV4zaXzExGe%2FNNQ4%2BZ05m5ZITIIMRle34BuixdVo3U3ld9zLi%2BFu73FfLTz6caK%2F7gnc4k6domgg5WW4ALleNfT%2FIDXY8zXqTOHaZ0I41dePYT7et2f4BUsoPK8v3WWPzCGG%2Fb%2BNhR8f5lgZzmK%2BXyJX8J%2BwkJg%2F0dzEtC6iDOd%2FXwrFiFrq%2FY65LcUKFk1vSIQgL5M0e24IJpagkdjqWbhYAqePZQ8jQcUCW5x4Fj8y%2F5n4yYE8MVx695cPYcU0EaFLrDJjzAgpuGqE6tvHuTWaTDIw%2FTOBjqZAeCj79TwKf0M%2FpLFfHlQ48BdfLQ8JKaORgVCEdI8MlNgWw2v17M82b63urVIqZCQYIpbgAqIcB1obLIcMG3skbaO3McZv1kYhiF5FgPdg1HGc%2FlcQepYDZjBObaWrcWkYq7q6VdkxX51yowrbU1YKqq6pSwBzXytBpQ0En8kBAWF2Ep3CHQMp8du%2FodcoNY%2F%2Fd9KqK9Om%2Fj2aQ%3D%3D&Expires=1776102439)

**However**, looking at the actual adapter implementation in the diff: [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/9655754d-f59b-47c9-9875-7a6b66931cd5/paste.txt?AWSAccessKeyId=ASIA2F3EMEYEVYRA36TR&Signature=FHrIrtPSUtnmii2rqkPuDUbv%2F5Q%3D&x-amz-security-token=IQoJb3JpZ2luX2VjELH%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJGMEQCIGzZJ4zXBTDqFItLlScIEmC%2FUJtfR5gvZzvU2TKMsfWiAiAzs8SVt%2BYFMdHNAmNmUBqVSSaGbw5MfQOGZWRgchonhirzBAh6EAEaDDY5OTc1MzMwOTcwNSIMVETveIATlulFvm%2BHKtAEK95TV7AEZ%2BjwE%2F2Dmi2aiOdstbMF%2FW75qNLzNM%2FrGxE%2Bkdoq0mQt1DZgSFGFYMIFcnwX2ty2Nxb3Q9xCAovBn67nxaPahSSrtseG5piTJ%2F2lj9%2BfGVXJ%2FJ6N%2FTonInWYzhYbGXqNoZccVVnZYxIsy6JMyt0eKBYtQPK34vdsKaei4EeGoh6eX%2Bds%2FtNUBY2GjOHAVTBoEnZmwnVcJC00p9FtDbv5xDI8T6n1Mm3FlViN6YVvYR0YLF1zc9W9c992hPvXiDElEw64AJUAfj%2BZd%2FsSolPqzKpZ98FHnIZpSX5x3CFIeow2GlX2haoHpzq2%2BYkHrRVtC9lOzjsD%2FjkLmcUUvTpDWHVuV2Q5g7yvQ2A%2Bw%2BNR0CHJoXr1w8YeDzwh98UHs9WyIJu%2Fby6ua535zyhxJ%2BWTWZEi6hhj9l4lqYouzR7ZmeM591URB4DPfUaNHoCBkJFZU1DCur8JTZ3ZvYrNt4SRI%2FLJ0GAO%2FdB0A7ypqqeEpf00xm87Di1tjCV4zaXzExGe%2FNNQ4%2BZ05m5ZITIIMRle34BuixdVo3U3ld9zLi%2BFu73FfLTz6caK%2F7gnc4k6domgg5WW4ALleNfT%2FIDXY8zXqTOHaZ0I41dePYT7et2f4BUsoPK8v3WWPzCGG%2Fb%2BNhR8f5lgZzmK%2BXyJX8J%2BwkJg%2F0dzEtC6iDOd%2FXwrFiFrq%2FY65LcUKFk1vSIQgL5M0e24IJpagkdjqWbhYAqePZQ8jQcUCW5x4Fj8y%2F5n4yYE8MVx695cPYcU0EaFLrDJjzAgpuGqE6tvHuTWaTDIw%2FTOBjqZAeCj79TwKf0M%2FpLFfHlQ48BdfLQ8JKaORgVCEdI8MlNgWw2v17M82b63urVIqZCQYIpbgAqIcB1obLIcMG3skbaO3McZv1kYhiF5FgPdg1HGc%2FlcQepYDZjBObaWrcWkYq7q6VdkxX51yowrbU1YKqq6pSwBzXytBpQ0En8kBAWF2Ep3CHQMp8du%2FodcoNY%2F%2Fd9KqK9Om%2Fj2aQ%3D%3D&Expires=1776102439)

```java
@Transactional(TxType.REQUIRES_NEW)
public Optional<CheckpointDto> doFindLatestAwaitingApprovalWithPrefix(String sessionPrefix) {
    em.clear();
    return em.createQuery(
        "SELECT c FROM AgentCheckpoint c WHERE c.sessionId LIKE :prefix ...",
        AgentCheckpoint.class)
        .setParameter("prefix", sessionPrefix)   // ← BUG: no % wildcard!
        .setParameter("status", CheckpointStatus.AWAITING_APPROVAL)
```

The `LIKE` without `%` is a **silent exact-match** — semantically identical to `= :prefix`. Cron session IDs follow the pattern `cron-{cronId}-{executionUuid}`, so `LIKE 'cron-abc'` will **never match** `cron-abc-550e8400-e29b-...`. This is the original C6 bug still unfixed.

**Action required — one-line fix in `doFindLatestAwaitingApprovalWithPrefix`:**
```java
// BEFORE (current — broken):
.setParameter("prefix", sessionPrefix)

// AFTER (correct):
.setParameter("prefix", sessionPrefix + "%")
```

***

### Discrepancy 3 — Audit timing for `CHECKPOINT_CREATED`: **The prompt is correct. Move audit AFTER the save.**

This is not just cosmetic — there is a semantic correctness issue: [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/ec9768e7-d9ce-423c-878b-60c24af3ea4a/rsrini7-arcclawinternal-main.txt)

```
Timeline A (current — audit BEFORE save):
  1. Detect: existing is empty → CREATE path
  2. auditService.recordWithIdempotency(CHECKPOINT_CREATED)   ← audit written
  3. storeAdapter.save(dto)  ← save FAILS (BUSY, crash, etc.)
  Result: audit says "checkpoint was created" — but it never was. False audit trail.

Timeline B (correct — audit AFTER save):
  1. Detect: existing is empty → CREATE path  
  2. storeAdapter.save(dto)  ← save succeeds
  3. auditService.recordWithIdempotency(CHECKPOINT_CREATED)   ← audit written
  Result: audit accurately reflects what happened. If audit fails, idempotency key allows replay.
```

The `TELEMETRY` tier's `recordWithIdempotency` is fire-and-forget — if it fails after a successful save, the idempotency key prevents duplicates on retry. The reverse is not true: you cannot un-write an audit event for a save that never happened. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/a2ea908b-4b61-42b8-baf8-4b8fc45ce67f/paste.txt)

**Action required:** Move the `auditService.recordWithIdempotency(CHECKPOINT_CREATED, ...)` call to **after** `storeAdapter.save(dto)` returns successfully in the `else` (CREATE) branch.

***

### Discrepancy 4 — `TemporalUtils` / `InstantToTextConverter` relocation: **Your approach is better than the original plan. Keep it.**

What you did is architecturally superior to what the prompt described: [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/85542380/9655754d-f59b-47c9-9875-7a6b66931cd5/paste.txt?AWSAccessKeyId=ASIA2F3EMEYEVYRA36TR&Signature=FHrIrtPSUtnmii2rqkPuDUbv%2F5Q%3D&x-amz-security-token=IQoJb3JpZ2luX2VjELH%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJGMEQCIGzZJ4zXBTDqFItLlScIEmC%2FUJtfR5gvZzvU2TKMsfWiAiAzs8SVt%2BYFMdHNAmNmUBqVSSaGbw5MfQOGZWRgchonhirzBAh6EAEaDDY5OTc1MzMwOTcwNSIMVETveIATlulFvm%2BHKtAEK95TV7AEZ%2BjwE%2F2Dmi2aiOdstbMF%2FW75qNLzNM%2FrGxE%2Bkdoq0mQt1DZgSFGFYMIFcnwX2ty2Nxb3Q9xCAovBn67nxaPahSSrtseG5piTJ%2F2lj9%2BfGVXJ%2FJ6N%2FTonInWYzhYbGXqNoZccVVnZYxIsy6JMyt0eKBYtQPK34vdsKaei4EeGoh6eX%2Bds%2FtNUBY2GjOHAVTBoEnZmwnVcJC00p9FtDbv5xDI8T6n1Mm3FlViN6YVvYR0YLF1zc9W9c992hPvXiDElEw64AJUAfj%2BZd%2FsSolPqzKpZ98FHnIZpSX5x3CFIeow2GlX2haoHpzq2%2BYkHrRVtC9lOzjsD%2FjkLmcUUvTpDWHVuV2Q5g7yvQ2A%2Bw%2BNR0CHJoXr1w8YeDzwh98UHs9WyIJu%2Fby6ua535zyhxJ%2BWTWZEi6hhj9l4lqYouzR7ZmeM591URB4DPfUaNHoCBkJFZU1DCur8JTZ3ZvYrNt4SRI%2FLJ0GAO%2FdB0A7ypqqeEpf00xm87Di1tjCV4zaXzExGe%2FNNQ4%2BZ05m5ZITIIMRle34BuixdVo3U3ld9zLi%2BFu73FfLTz6caK%2F7gnc4k6domgg5WW4ALleNfT%2FIDXY8zXqTOHaZ0I41dePYT7et2f4BUsoPK8v3WWPzCGG%2Fb%2BNhR8f5lgZzmK%2BXyJX8J%2BwkJg%2F0dzEtC6iDOd%2FXwrFiFrq%2FY65LcUKFk1vSIQgL5M0e24IJpagkdjqWbhYAqePZQ8jQcUCW5x4Fj8y%2F5n4yYE8MVx695cPYcU0EaFLrDJjzAgpuGqE6tvHuTWaTDIw%2FTOBjqZAeCj79TwKf0M%2FpLFfHlQ48BdfLQ8JKaORgVCEdI8MlNgWw2v17M82b63urVIqZCQYIpbgAqIcB1obLIcMG3skbaO3McZv1kYhiF5FgPdg1HGc%2FlcQepYDZjBObaWrcWkYq7q6VdkxX51yowrbU1YKqq6pSwBzXytBpQ0En8kBAWF2Ep3CHQMp8du%2FodcoNY%2F%2Fd9KqK9Om%2Fj2aQ%3D%3D&Expires=1776102439)

- `InstantToTextConverter` moved to `arcclaw-persistence-sqlite` with **`@Converter(autoApply = true)`** — core entities (`AgentCronEntity`, `AgentSkillEntity`, `RagChunkEntity`, etc.) now have zero SQLite awareness, no `@Convert` annotations
- `TemporalUtils.formatIsoLex()` in core gives a backend-agnostic formatting utility that the SQLite converter delegates to
- `AgentCronRepository` upgraded from native SQL (with manual `InstantToTextConverter.format()` calls) to JPQL — cleaner, portable

This is the correct approach. **One thing to document in Architecture Invariants** to protect future developers:

> **Invariant 5.20** — `@Converter(autoApply = true)` on `InstantToTextConverter` in `arcclaw-persistence-sqlite` must remain SQLite-only. The `arcclaw-persistence-postgres` module MUST NOT add a competing `@Converter(autoApply = true)` for `Instant` — PostgreSQL handles `timestamptz` natively with no converter needed. Having two auto-apply converters for the same type on the classpath simultaneously causes a Hibernate boot conflict.

***

## Complete Action Checklist

Only 3 targeted changes remain:

```
FIX 1 (markFailed audit) ─── StatefulAgentStoreImpl.markFailed()
  → Add auditService.recordWithIdempotency(CHECKPOINT_FAILED, ..., "CP-FAILED-" + checkpointId)
    after storeAdapter.save(updated), inside the ifPresent lambda
    (mirror markOrphaned's existing pattern exactly)

FIX 2 (LIKE wildcard)    ─── SqliteCheckpointStoreAdapter.doFindLatestAwaitingApprovalWithPrefix()
  → Change .setParameter("prefix", sessionPrefix)
         to .setParameter("prefix", sessionPrefix + "%")

FIX 3 (audit timing)     ─── StatefulAgentStoreImpl.save() CREATE branch
  → Move auditService.recordWithIdempotency(CHECKPOINT_CREATED, ...)
    from BEFORE storeAdapter.save(dto) to AFTER it
```

Everything else you've implemented is correct and should not be changed.