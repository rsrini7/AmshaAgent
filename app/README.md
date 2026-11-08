# Amsha Application Module (`amsha-app`)

Assembles the application runtime and owns the tool-attempt schedulers.

## Tool Attempt Schedulers

| Property | Environment variable | Default | Description |
|---|---|---|---|
| `amsha.tools.attempt.retention` | `AMSHA_TOOLS_ATTEMPT_RETENTION` | `30d` | Retention window for terminal tool attempts, locks, resolutions, and delivered outbox rows |
| `amsha.tools.attempt.retention-enabled` | `AMSHA_TOOLS_ATTEMPT_RETENTION_ENABLED` | `true` | Enable scheduled background pruning of retention-eligible tool attempt records |
| `amsha.tools.attempt.retention-cron` | `AMSHA_TOOLS_ATTEMPT_RETENTION_CRON` | `0 0 3 * * ?` | Cron expression for daily tool attempt retention sweep (03:00 daily) |
| `amsha.tools.attempt.audit-outbox-enabled` | `AMSHA_TOOLS_ATTEMPT_AUDIT_OUTBOX_ENABLED` | `true` | Enable scheduled sweep of the tool attempt audit outbox |
| `amsha.tools.attempt.audit-outbox-cron` | `AMSHA_TOOLS_ATTEMPT_AUDIT_OUTBOX_CRON` | `0 * * * * ?` | Cron expression for the audit outbox sweep (every minute) |

`ToolAttemptRetentionScheduler` (daily 03:00 prune of terminal attempts, locks, resolutions, and delivered outbox rows older than the retention window) and `ToolAttemptAuditOutboxScheduler` (1-minute lease-based delivery sweep) live under `com.amsha.app.scheduler`.
