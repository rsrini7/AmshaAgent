Yes — I think adding **Scheduler** and **Audit** to your existing ArcClaw UI is the right move, and it is a better next step than introducing JobRunr or migrating to JBeret. You already persist the core data in DB, and Quarkus itself recommends Quartz only for more advanced persistent/clustering scenarios, while the default scheduler is lightweight and programmatic jobs are already supported through the Scheduler API. [quarkus](https://quarkus.io/guides/scheduler-reference)

## Recommendation

You should keep your current DB-backed scheduler and extend the UI with two new first-class sections: **Scheduler** and **Audit**. That aligns with your existing architecture — Agents, Approvals, Chat, and Settings already expose operational state, and Scheduler/Audit would complete the admin surface for background work and traceability.

This gives you most of the useful JobRunr experience without taking on another runtime model: scheduled jobs table, run history, retry state, approval-required state, and audit timelines — all from your own DB and semantics. JobRunr’s main value is durable job state and retries, not the UI itself, so if your persistence model is already solid, your own UI is enough. [jobrunr](https://www.jobrunr.io/en/documentation/)

## Minimum features

For a **minimum JobRunr-like scheduler**, I’d add these DB-backed capabilities:

- Job list: scheduled, due, running, awaiting approval, completed, failed, canceled.
- Next fire time, last fire time, retry count, last error, channel, session id, originating user/chat.
- Actions: retry now, pause, resume, cancel, force-run, and view linked approvals/chat/audit trail.
- Run history table per cron/job execution, not just the parent schedule row.
- Misfire handling rule, dedupe/lease lock, max-attempts, exponential backoff, dead-letter/final-failed state. [quarkus](https://quarkus.io/guides/scheduler-reference)

For **Audit**, expose:
- Audit event timeline by session, cron id, approval id, tool name, channel, and actor.
- Filters for event type (`CRON_CREATED`, `LOOP_AWAITING_APPROVAL`, `TOOL_INVOKED`, `CHECKPOINT_COMPLETED`, etc.).
- Drill-through from Scheduler row → executions → approvals → audit trail → chat transcript.

## UI sections

I’d add these two tabs:

### Scheduler
- Table: `Name/Goal`, `Status`, `Next Fire`, `Last Fire`, `Retries`, `Channel`, `Session/Cron Id`, `Actions`.
- Detail drawer: schedule metadata, latest run, prior runs, pending approval state, linked audit events, raw payload.
- Secondary views: “Due soon”, “Awaiting approval”, “Failed”, “Paused”.

### Audit
- Timeline-first UI, not just a raw table.
- Filters on top: date range, session id, cron id, event type, actor, channel, outcome.
- Click an event to show structured JSON payload and cross-links into Chat / Approvals / Scheduler. 

That structure fits your current UI mental model better than adding a generic external dashboard.

## Suggested prompt

Use this prompt with Claude/GPT inside your repo worktree to implement the next sprint in your existing UI:

> You are working on ArcClaw, a Quarkus + SQLite application with an existing admin UI that currently has tabs/pages for Agents, Approvals, Chat, and Settings. We already store scheduler data and audit logs in the database.  
>  
> Goal: extend the existing UI and backend to add two new first-class sections: **Scheduler** and **Audit**, using only the current ArcClaw stack and existing database models/endpoints where possible. Do **not** add JobRunr, JBeret, or other new scheduling/dashboard dependencies.  
>  
> Requirements:
> 1. Add a **Scheduler** page/tab to the current UI navigation beside Agents, Approvals, Chat, and Settings.  
> 2. Add an **Audit** page/tab to the current UI navigation.  
> 3. Backend should expose REST endpoints that read from existing DB tables/entities for:
>    - schedules / cron jobs
>    - schedule execution history
>    - audit events/logs
>    - linked approvals for a run
> 4. Scheduler page must show:
>    - schedule id / cron id
>    - goal / summary
>    - status (`SCHEDULED`, `DUE`, `RUNNING`, `AWAITING_APPROVAL`, `COMPLETED`, `FAILED`, `PAUSED`, `CANCELLED`)
>    - next fire time
>    - last fire time
>    - retry count
>    - channel type / platform id
>    - originating user/session
>    - actions: retry-now, pause, resume, cancel, trigger-now
> 5. Scheduler detail drawer/page must show:
>    - parent schedule metadata
>    - execution history list
>    - latest error
>    - pending approval info if any
>    - linked audit trail
>    - links to related chat/session/approval pages
> 6. Audit page must show:
>    - timeline/table of audit events from DB
>    - filters: sessionId, cronId, eventType, actor, channel, date range
>    - detail panel with structured payload/JSON
>    - deep links to related Scheduler, Approvals, and Chat entities
> 7. Reuse existing UI patterns, styling, layout, table components, and API client utilities already present in ArcClaw UI. Keep visual consistency with the current admin console.
> 8. Prefer incremental implementation:
>    - step 1: backend read endpoints
>    - step 2: Scheduler list page
>    - step 3: Scheduler detail drawer
>    - step 4: Audit list/timeline page
>    - step 5: cross-links and actions
> 9. If DB schema is missing anything essential, propose the smallest additive schema changes only.
> 10. Produce:
>    - code changes
>    - endpoint list
>    - any schema migrations
>    - short rationale for each addition
>  
> Important design constraints:
> - Keep ArcClaw’s custom scheduler architecture.
> - Treat this as building a lightweight internal operations console similar to JobRunr’s useful features, but on ArcClaw’s own data model.
> - Do not introduce external dashboard frameworks.
> - Keep implementation production-safe for multi-session, approval-heavy agent workflows.

## Data model additions

If your current DB is not enough, the smallest useful additions would be:

- `agent_cron_run` (one row per actual execution)
- `attempt_count`
- `last_error`
- `last_started_at`
- `last_finished_at`
- `paused`
- `cancelled_at`
- `awaiting_approval`
- `linked_checkpoint_id`
- `linked_approval_session_id`

For audit, if you already persist event payloads, great; if not, add a `payload_json` column so the Audit page can show structured details without reverse-parsing log text. That will make the UI much more useful than plain strings.

## My take

This is the correct direction. Add **Scheduler** and **Audit** to the current UI, make them DB-driven, and you’ll cover the missing operator workflows: “what is scheduled?”, “what ran?”, “why is it stuck?”, “who approved it?”, and “what exactly happened?”. That closes the biggest usability gap in ArcClaw without adding another execution framework. [jobrunr](https://www.jobrunr.io/en/documentation/)

