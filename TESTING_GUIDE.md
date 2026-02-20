# ArcClaw Verification & Testing Guide

This guide details how to verify the end-to-end functionality of ArcClaw.

## Prerequisites
1. Ensure the docker-compose stack is running:
   ```bash
   docker compose up -d
   ```
2. Ensure the Quarkus application is running:
   ```bash
   mvnw quarkus:dev -pl arcclaw-app
   ```

## E2E API Workflow

### 1. Create a Session
Create a new agent session to obtain a unique Session ID.

```bash
curl -X POST http://localhost:8080/api/v1/sessions
```
*Expected Response:*
```json
{"sessionId":"<extracted-uuid-here>"}
```

*Set your Session ID as an environment variable for the rest of the commands:*
```bash
# Windows (PowerShell)
$SESSION_ID="<extracted-uuid-here>"
# Linux/macOS
export SESSION_ID="<extracted-uuid-here>"
```

### 2. Run an Agent (Asynchronous)
Execute the Grok coordinator agent with a specific goal. This endpoint is non-blocking.

```bash
# Windows
curl -X POST http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async `
     -H "Content-Type: application/json" `
     -d '{ "goal": "Analyze the openclaw repository on github and tell me what it does", "agentRole": "grok" }'

# Linux/macOS
curl -X POST http://localhost:8080/api/v1/sessions/$SESSION_ID/run-async \
     -H "Content-Type: application/json" \
     -d '{"goal": "Analyze the openclaw repository on github and tell me what it does", "agentRole": "grok"}'
```
*Expected Response:* `HTTP 202 Accepted`

### 3. Poll the Session Status
Check the status of the asynchronous agent execution. Run this repeatedly to watch the agent's progress.

```bash
# Windows
curl http://localhost:8080/api/v1/sessions/$SESSION_ID/status/grok

# Linux/macOS
curl http://localhost:8080/api/v1/sessions/$SESSION_ID/status/grok
```
*Expected Response:* JSON payload containing the current `status` (e.g., `IN_PROGRESS`, `COMPLETED`) and a `steps` array detailing the agent's thoughts and actions.

## Tracing (Jaeger)

ArcClaw uses OpenTelemetry to trace requests across the API, the reasoning engine, and external tool executions.

To view traces:
1. Open the Jaeger UI in your browser at: [http://localhost:16686](http://localhost:16686)
2. In the **Service** dropdown, select `arcclaw`.
3. Click **Find Traces** to view the timeline of requests.
4. Click on an individual trace to see the span hierarchy, which will include the HTTP request, the agent loop, LLM interactions, and executed Sandbox tool operations.
