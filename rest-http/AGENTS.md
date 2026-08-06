# REST LLM Validation Guide

This directory contains REST validators for a running Amsha instance. The
validators use the API key and base URL from `scripts/common_env.sh`; do not
hardcode, print, or commit credentials.

## Delegation validation

Start the JVM or native runtime first. Validators default to
`http://localhost:8080`; set `TEST_BASE` when the runtime uses another URL.

Run the complete built-in persona delegation check:

```bash
DELEGATION_TIMEOUT_SECONDS=90 \
DELEGATION_AUDIT_WAIT_SECONDS=45 \
DELEGATION_PACE_SECONDS=0 \
rtk bash rest-http/scripts/run_all_validations.sh --delegation
```

Run Sentinel alone when diagnosing its security delegation:

```bash
DELEGATION_TIMEOUT_SECONDS=180 \
DELEGATION_AUDIT_WAIT_SECONDS=60 \
DELEGATION_PACE_SECONDS=0 \
DELEGATION_ROLES=sentinel \
rtk bash rest-http/scripts/validate_delegation.sh
```

Run a subset by comma-separated role ID:

```bash
DELEGATION_PACE_SECONDS=0 \
DELEGATION_ROLES=coordinator,researcher,coder \
rtk bash rest-http/scripts/validate_delegation.sh
```

The matrix covers all 12 built-in personas:

| Persona | Delegation target |
| --- | --- |
| coordinator | researcher |
| researcher | coordinator |
| coder | researcher |
| debugger | coder |
| architect | coder |
| synthesizer | architect |
| optimizer | debugger |
| sentinel | debugger |
| healer | optimizer |
| protector | researcher |
| purifier | healer |
| nurturer | researcher |

## Delegation environment variables

| Variable | Default | Purpose |
| --- | ---: | --- |
| `TEST_BASE` | `http://localhost:8080` | REST runtime base URL |
| `DELEGATION_ROLES` | all 12 | Comma-separated role subset |
| `DELEGATION_TIMEOUT_SECONDS` | `180` | Maximum `run-async` request time |
| `DELEGATION_AUDIT_WAIT_SECONDS` | `90` | Total dispatch-audit observation window |
| `DELEGATION_AUDIT_POLL_SECONDS` | `2` | Delay between audit polls |
| `DELEGATION_AUDIT_REQUEST_TIMEOUT_SECONDS` | `5` | Timeout for one audit-list request |
| `DELEGATION_APPROVAL_REQUEST_TIMEOUT_SECONDS` | `5` | Timeout for one approval request |
| `DELEGATION_PACE_SECONDS` | `1` | Delay between persona sessions |
| `AMSHA_RATE_LIMIT_WAIT_SECONDS` | `62` | Retry delay for normal requests receiving HTTP 429 |
| `VALID_KEY` | from `common_env.sh` | API key override; keep it out of logs |

The full-run values used during local verification were:

```text
DELEGATION_TIMEOUT_SECONDS=90
DELEGATION_AUDIT_WAIT_SECONDS=45
DELEGATION_PACE_SECONDS=0
```

The focused Sentinel values were:

```text
DELEGATION_TIMEOUT_SECONDS=180
DELEGATION_AUDIT_WAIT_SECONDS=60
DELEGATION_PACE_SECONDS=0
DELEGATION_ROLES=sentinel
```

## What the delegation validator proves

- Each role gets a fresh isolated session.
- The role is asked to invoke `garuda` for an out-of-domain task.
- The run is submitted through `run-async` in the background.
- The validator requires a `DELEGATION_DISPATCHED` audit event.
- HTTP 403 without a dispatch event and `TOOL_BLOCKED` are failures.
- Approval-required roles are approved through the REST checkpoint endpoint
  while the audit poll continues.
- Audit evidence is read from `/api/v1/audit?sessionId=...&size=200`. The
  session-specific `/audit/session/{id}` form can return 404 after normal
  session cleanup even when audit rows still exist.

`run-async` may report `pending`, HTTP 408, HTTP 000, or another incomplete
response after dispatch because the delegated specialist is still running or
waiting for approval. Such a result passes only when the dispatch audit event
is present and no block event was observed. The audit boundary, not specialist
completion, is the capability assertion.

Response bodies are kept in temporary files and are never printed. If the
runtime is busy, increase `DELEGATION_AUDIT_WAIT_SECONDS` or reduce polling
frequency by increasing `DELEGATION_AUDIT_POLL_SECONDS`; do not remove the
audit requirement or bypass approval.

## Other REST validators

Use the master validator to list available suites or run the LLM group:

```bash
rtk bash rest-http/scripts/run_all_validations.sh --list
rtk bash rest-http/scripts/run_all_validations.sh --llm
```

The master validator must return nonzero when any selected suite fails. After
editing a shell validator, run:

```bash
rtk bash -n rest-http/scripts/validate_delegation.sh
rtk git diff --check
```

Run the documentation verifier after changing this file:

```bash
rtk python3 ./scripts/verify_docs.py
```
