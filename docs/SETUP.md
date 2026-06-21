# Setup

**First time?** See [QUICK_START.md](./QUICK_START.md) for a 30-second dev setup.

ArcClaw has two setup guides depending on your preference:

| Guide | For |
|---|---|
| [MISE_SETUP.md](./MISE_SETUP.md) **(recommended)** | Automated setup via mise task runner — one command installs JDK, Podman, dependencies |
| [MANUAL_SETUP.md](./MANUAL_SETUP.md) | Manual setup — prerequisites, OS-specific instructions, Docker Compose, PostgreSQL config |

---

## At a Glance

```bash
# mise (recommended)
mise install
mise run build
mise run dev:chat

# or manual
./mvnw quarkus:dev -pl arcclaw-app -am "-Dquarkus.args=chat"
```

See [MISE_SETUP.md](./MISE_SETUP.md) or [MANUAL_SETUP.md](./MANUAL_SETUP.md) for complete instructions.
