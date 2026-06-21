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
# Edit .env: set AMSHA_LLM_API_KEY (Groq key starting with gsk_)
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
