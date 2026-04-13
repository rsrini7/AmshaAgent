In Quarkus, dev-mode hot-reload **restarts the application context**, which means `@PostConstruct` is re-invoked automatically. The startup-sorted cache is already correct for hot-reload without needing any extra hook. Let me remove the orphan method and instead document the design decision clearly.

Viewed ReflexRegistry.java:1-98
Edited ReflexRegistry.java

Now let me also check whether the metric names in `FAQ.md` need updating to reflect the `arcclaw.*` prefix:

Searched for "governance.budget"
Searched for "retry.backlog"
Searched for "governance.budget"
Searched for "persistence.sqlite"

Good — the old un-prefixed metric names are already gone from these files. Let me do a broader search to confirm there are no stale references anywhere:

Searched for "arcclaw.retry"
Searched for "arcclaw.governance"
Searched for "arcclaw.persistence"
Searched for "grafana|prometheus|alert.*yaml|alert.*yml"

Good — all Java metric names are already properly prefixed with `arcclaw.*`. Now let me check the Prometheus config and TESTING_GUIDE for stale metric references:

Searched for "retry"
Viewed TESTING_GUIDE.md:1227-2026
Searched for ""retry\.backlog|governance\.budget\.map|persistence\.sqlite\.retry""

Now let me check the README for any stale metric references:

Viewed README.md:409-1208
Searched for "(?<!arcclaw\.)(retry\.backlog|governance\.budget\.map|persistence\.sqlite\.retry)"

Now let me verify the final state of the file:

Viewed ReflexRegistry.java:1-102

Both tasks are now properly complete. Here's the final summary:

### Task 1: `ReflexRegistry` per-request sorting overhead ✅

**Decision: Startup cache is correct; no `invalidateCache()` needed.**

- The `sortedReflexes` immutable list is built once in `@PostConstruct` — `O(N log N)` at boot, `O(1)` per request.
- **Hot-reload safety**: Quarkus dev-mode restarts the entire CDI application context on code changes, which re-fires `@PostConstruct` automatically. No explicit invalidation hook is needed.
- Removed the orphan `invalidateCache()` method (no caller = dead code) and documented the design rationale directly in the Javadoc on `init()`.

### Task 2: Metric naming alignment ✅

**Decision: No-op — no external consumers use old names.**

- All Java metric emitters already use `arcclaw.*` prefixes:
  - `arcclaw.retry.backlog.max_age_seconds`, `arcclaw.retry.orphaned.count` — `RateLimitRetryScheduler`
  - `arcclaw.governance.budget.map.size`, `arcclaw.governance.budget.evictions` — `GovernanceLayer`
  - `arcclaw.persistence.sqlite.retry.exhausted` — `SqliteRetryTemplate`
- `prometheus.yml` only defines scrape targets (no alert rules with old metric names).
- `README.md` and `TESTING_GUIDE.md` already reference `arcclaw.*` prefixed metric names.
- No Grafana JSON dashboards or alert YAML files exist in the repository.

---

