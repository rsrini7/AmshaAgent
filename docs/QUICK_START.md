# Quick Start

## Prerequisites

- **JDK 25** (Temurin or GraalVM)
- **Docker or Podman** (optional, for sandbox isolation)
- **Mise** (recommended for task automation)

## 30-Second Setup (Dev Mode)

```bash
git clone <repo>
cd amsha

# Copy and configure .env
cp .env.example .env
# Edit .env: set AMSHA_GROQ_API_KEY (Groq key starting with gsk_)
#          set AMSHA_VAULT_PEPPER (32+ random chars)

# Start in chat mode with hot reload
./mvnw quarkus:dev -pl amsha-app -am "-Dquarkus.args=chat"
```

## Other Modes

```bash
# One-shot ask
./mvnw quarkus:dev -pl amsha-app -am "-Dquarkus.args=ask 'list all github repos'"

# REST daemon
./mvnw quarkus:dev -pl amsha-app -am "-Dquarkus.args=serve"

# Telegram bot (requires TELEGRAM_BOT_TOKEN)
./mvnw quarkus:dev -pl amsha-app -am "-Dquarkus.args=attach telegram"
```

## Packaged JAR

```bash
./mvnw clean package -DskipTests
java --enable-native-access=ALL-UNNAMED \
  -jar app/target/quarkus-app/quarkus-run.jar chat
```

## Native Image

```bash
# With mise (auto-switches to GraalVM)
mise run build:native-local
./dist/amsha ask "hello"

# Or manual Maven
./mvnw package -Dnative -Pnative
```

`build:native-local` builds and copies the UI before native packaging, then
runs Maven `verify`, including the project PMD gate. For a focused Java check:

```bash
python3 ./scripts/fix_fqns.py
./mvnw spotless:apply -q
./mvnw -pl <affected-module> -am pmd:check -DskipTests
mise run sonar:issues
```

### Native first-run onboarding smoke test

For a real first-owner onboarding test, set these runtime values in `dist/.env`:

```env
AMSHA_DATA_DIR=./data-onboarding-fresh
AMSHA_LLM_PROVIDER=infinite-loop
AMSHA_LLM_VAULT_PRIORITY=true
AMSHA_UI_SKIP_ONBOARDING=false
```

Use a new data directory when the previous attempt already reached `CLAIMED`; do not delete an existing data directory implicitly. After rebuilding and packaging, start the native runner from `dist` with `./amsha-sqlite serve`, then open `http://localhost:8080`. The claim response must establish the `AMSHA_API_KEY` cookie. On local HTTP it is intentionally not `Secure`, including when the request arrives through IPv6 loopback (`http://[::1]`), so the browser can reuse it. An authenticated `/api/v1/onboarding/status` response should report `vaultPriority: true`.

If the browser loses the owner cookie, `bootstrap/status` remains `CLAIMED` and onboarding status returns `401`; this is expected and prevents a second anonymous owner claim. Restore the owner credential or restart the test with a new data directory.

## Docker Compose

```bash
mise run compose:up  # or: docker compose up -d
```

---

## Next Steps

| Doc | What it covers |
|---|---|
| [SETUP.md](./SETUP.md) | Detailed setup, prerequisites, OS-specific guide |
| [CONFIGURATION.md](./CONFIGURATION.md) | All environment variables |
| [ARCHITECTURE.md](./ARCHITECTURE.md) | Architecture overview |

For the complete CLI-versus-web onboarding lifecycle, configuration precedence,
bootstrap authentication, recovery, and reset behavior, see
[ONBOARDING.md](./ONBOARDING.md).
