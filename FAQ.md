# ArcClaw v1.0 — Frequently Asked Questions (FAQ)

This document explains the "Why" behind ArcClaw's architectural decisions, operational quirks, and runtime behaviors. If you are experiencing an actual crash or stack trace, please refer to [`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md) instead.

---

## 🏛️ Architecture & Design

### Q: Why does ArcClaw use Podman instead of Docker Desktop?
ArcClaw executes AI-generated tools inside a completely isolated sandbox (`ProcessIsolationExecutor`). To do this securely, it uses a **Container-in-Container** architecture. 
If we used Docker, we would have to mount the root Docker daemon socket (`/var/run/docker.sock`) into the ArcClaw container, which is a massive security risk. Podman is daemonless and rootless by design. It allows ArcClaw to spawn nested, isolated containers as standard user processes without requiring root privileges on the host machine.

### Q: Why use SQLite instead of a scalable database like PostgreSQL?
ArcClaw relies on `StatefulAgentStore` to checkpoint the LLM's `ReasoningStep` history at every iteration. 
1. **Zero-Latency:** Writing to a local SQLite file avoids network latency during the high-speed Agent Loop.
2. **Concurrency:** By enabling SQLite `WAL` (Write-Ahead Logging) mode and `busy_timeout` via our `SqliteWalConfigurer`, SQLite handles concurrent virtual thread writes flawlessly.
3. **Portability:** It allows ArcClaw to be deployed as a single, self-contained binary without external database dependencies.

### Q: Why do you use Virtual Threads (`@RunOnVirtualThread`) for the REST endpoints?
Agent loops are fundamentally I/O bound. They spend 95% of their time waiting for the LLM API to respond or waiting for a Podman sandbox process to finish executing. If we used standard Quarkus worker threads, a few dozen concurrent agent sessions would exhaust the thread pool. Virtual Threads (JEP 491) allow us to handle thousands of concurrent agents with almost zero memory overhead.

---

## ⚙️ Runtime & AI Operations

### Q: My Agent loop randomly failed with: *"System Error: You provided invalid JSON formatting"*. What happened?
You encountered a **JSON Hallucination**. LLMs (especially smaller local models like `llama3.1:8b`) sometimes generate invalid JSON when requesting a tool (e.g., leaving a trailing comma or unescaped quote). 
Instead of ArcClaw swallowing the Jackson `JsonParseException` and crashing, the `LangChainReasoningEngine` intercepts the bad JSON and returns this specific error back to the LLM as a "System Error" observation. This allows the LLM to realize its mistake and self-correct the JSON on the next iteration.

### Q: What happens if a Sandbox tool returns a massive payload (e.g., reading a 10,000-line file)?
ArcClaw employs **Defensive Truncation**. If an observation is fed back into the LLM at its full length, it will instantly blow past the LLM's context window (usually 8k tokens for local models), causing an unrecoverable crash. ArcClaw automatically truncates any tool observation exceeding 4,000 characters before appending it to the reasoning history.

### Q: I am getting `Permission denied` when SQLite tries to write to the `arcclaw_data` Docker volume. Why?
Docker daemons run as root and create named volumes as `root:root`. However, ArcClaw's `Dockerfile.native` aggressively drops privileges and runs as the non-root `arcclaw` user (UID 1000). 
**The Fix:** We use an Init-Container pattern. In `docker-compose.yaml`, the `init-perms` service briefly boots as root, runs `chown -R 1000:1000 /app/data` on the volume, and exits before the main ArcClaw container starts.

---

## 💻 Local Development & Environment

### Q: Can I run ArcClaw natively on Windows?
Yes, but you **must** use WSL2 (Windows Subsystem for Linux). Because ArcClaw relies on Linux user namespaces to isolate the AI sandbox, it cannot run natively on the Windows NT kernel. 
We highly recommend installing **Podman Desktop**, which automatically configures the WSL2 backend and places the `podman` CLI in your Windows PATH so ArcClaw can execute sandbox commands seamlessly.

### Q: I'm writing custom code using `@RunOnVirtualThread` but my `@WorkspaceScoped` beans are returning `null`. Why?
When Quarkus offloads execution to a Virtual Thread, standard `ThreadLocal` storage is left behind on the carrier thread. To fix this, ArcClaw uses the MicroProfile Context Propagation API. Ensure that `mp.context.propagators=Quarkus, CDI, JAX-RS` remains in your `application.yaml` so the CDI context traverses the thread boundary securely.

### Q: Why does the `containers.conf` force the `vfs` storage driver?
When running Podman *inside* a container (nested containerization), standard storage drivers like `overlayfs` often fail due to host kernel restrictions on unprivileged mounts. The Virtual File System (`vfs`) driver is a fallback that guarantees the sandbox will work in any environment. 
*Note: `vfs` creates deep copies of container images. You should periodically run `podman system prune -f` to free up disk space.*