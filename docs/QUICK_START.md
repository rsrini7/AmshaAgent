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
Amsha does not fetch model files during inference. The packaged binary includes
model-management commands, so Mise is not required:

```bash
./amsha-sqlite models list
./amsha-sqlite models install mpnet       # ONNX embeddings
./amsha-sqlite models install whisper     # local speech-to-text
./amsha-sqlite models install bge-reranker
./amsha-sqlite models install all
./amsha-sqlite models verify all
```

Downloads need an internet connection. MPNet is about 436 MB, Whisper about
43 MB, and BGE reranker about 1.1 GB. Files are stored under the configured
`AMSHA_ONNX_MODEL_ROOT` or `AMSHA_DATA_DIR` (default `data/models/`). A failed
install reports the model, cause, and retry command. Partial files are removed;
complete files that pass SHA-256 verification are kept and reused on retry.

Downloads are optional until the matching feature is enabled. MPNet is needed
for ONNX embeddings; Whisper is needed only when `AMSHA_STT_LOCAL_ENABLED=true`,
and BGE reranking only when `AMSHA_RAG_RERANKER_ENABLED=true`. If MPNet cannot
be downloaded, choose another provider in
`AMSHA_INFINITE_LOOP_EMBEDDING_PROVIDERS`, such as `ollama`, `jina`, or `openai`.
If no embedding provider is available, vector search reports that it is
unavailable and retrieval continues with keyword search. You can disable local
speech-to-text or reranking if those models are unavailable; reranking then
continues through the configured hybrid retrieval path.

If a feature is explicitly enabled with a required model missing and no
compatible fallback is configured, startup stops with the reason, install
command, and fallback guidance. The CLI also prints the diagnostic log path so
you can inspect the full startup details.

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
