# Quick Start

## Prerequisites

- **JDK 25** (Temurin or GraalVM)
- **Git** (to clone the repository)
- **Rootless Podman** (optional, for sandboxed code execution; not needed in dev mode)
- **Mise** (recommended for task automation)

## 30-Second Setup (Dev Mode)

```bash
git clone https://github.com/rsrini7/AmshaAgent.git
cd AmshaAgent

# Copy the configuration template, then edit .env before starting.
cp .env.example .env
```

Amsha does not select an LLM provider automatically. The first-run CLI setup asks you to choose one. For a non-interactive setup, set `AMSHA_LLM_PROVIDER` and the matching provider credentials in `.env`. For example, Groq uses `AMSHA_LLM_PROVIDER=groq` and `AMSHA_GROQ_API_KEY`; local providers such as Ollama and OMLX do not require a hosted API key. See the [configuration reference](./CONFIGURATION.md) for supported providers and their settings.

Replace the security-key placeholders `AMSHA_VAULT_PEPPER`,
`AMSHA_AUDIT_HMAC_KEY`, `AMSHA_API_KEYS_INTERNAL`, `AMSHA_API_KEYS_CI`, and
`AMSHA_AUTH_HMAC_KEY` with separate values generated using
`openssl rand -base64 32`. Keep them stable across restarts. The template enables
`AMSHA_AUTO_SEED_SECRETS=true` for a local data directory; on first boot, Amsha
stores a stable audit key in the vault before startup validation. Keep the vault
pepper stable after data has been created. If startup fails, the binary prints
its reason and diagnostic log path in the terminal.

```bash
# Start in chat mode with hot reload; dev mode runs without sandbox isolation.
./mvnw quarkus:dev -pl app -am "-Dquarkus.args=chat"
```

## Other Modes

```bash
# One-shot ask
./mvnw quarkus:dev -pl app -am "-Dquarkus.args=ask 'list all github repos'"

# REST daemon
./mvnw quarkus:dev -pl app -am "-Dquarkus.args=serve"

# Telegram bot (requires TELEGRAM_BOT_TOKEN)
./mvnw quarkus:dev -pl app -am "-Dquarkus.args=attach telegram"
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
./dist/amsha-sqlite ask "hello"

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

## Optional local ONNX models

Local embedding, reranking, and speech-to-text models are installed explicitly;
Amsha does not download them during inference. From a source checkout, install
models with Mise. These tasks verify the downloaded files and do not require a
configured LLM provider:

```bash
mise run models:install-mpnet
mise run models:install-bge-reranker
mise run models:provision-local-whisper
```

For MPNet, use the developer JAR or native binary built for your persistence
backend. Run the JAR command from the repository root; run a native command from
the directory containing the selected binary:

| Runtime | Install MPNet | Verify MPNet |
| --- | --- | --- |
| Developer JAR | `mise run jar:run-dev-dep -- models install mpnet` | `mise run jar:run-dev-dep -- models verify mpnet` |
| SQLite native | `./amsha-sqlite models install mpnet` | `./amsha-sqlite models verify mpnet` |
| PostgreSQL native | `./amsha-pg models install mpnet` | `./amsha-pg models verify mpnet` |
| Filesystem native | `./amsha-fs models install mpnet` | `./amsha-fs models verify mpnet` |

The source checkout also has a model-only MPNet installer,
`mise run models:install-mpnet`, which downloads and verifies the bundle without
building the JAR. The same `models install` and `models verify` commands accept
`whisper`, `bge-reranker`, and `all` selectors on the developer JAR and native
binaries. `all` downloads about 1.6 GB.

Downloads require internet access. MPNet is about 436 MB, Whisper about 43 MB,
and the BGE reranker about 1.1 GB. Files are stored under `AMSHA_ONNX_MODEL_ROOT`
or `AMSHA_DATA_DIR` (default `data/models/`). Failed installs report the cause and
retry command; incomplete files are removed, while complete verified files are
reused. MPNet is required when ONNX is the only configured embedding provider.
Whisper is required only when `AMSHA_STT_LOCAL_ENABLED=true`, and the reranker only
when `AMSHA_RAG_RERANKER_ENABLED=true`. If MPNet is unavailable, configure another
provider such as `ollama`, `jina`, or `openai` in
`AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS`. Without a usable vector provider,
retrieval continues with keyword search. If an explicitly enabled feature has no
model and no compatible fallback, startup prints the reason and installation
options, then stops. It also prints the diagnostic log path for further detail.

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
