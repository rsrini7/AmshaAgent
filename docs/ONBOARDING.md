# Onboarding Guide

This guide explains the two setup paths in Amsha Agent, what each path changes,
where state is stored, and how configuration becomes active at runtime.

## Choose a setup path

| Path | Best for | Writes `.env` | Stores durable onboarding state |
|---|---|---:|---:|
| CLI / native startup | Headless setup, operators, local development | Yes, when values are missing | No web draft |
| Web UI | Interactive LLM connections, routes, profiles, and remote MCP setup; deployment-only settings guidance | No | Yes, under `AMSHA_DATA_DIR` |

| Area | CLI / native startup | Web wizard |
|---|---|---|
| LLM providers | Selects all 21 public providers from the catalog. | Uses the same catalog; the internal `infinite-loop` test entry is hidden. CLI OAuth providers require host-side login first. |
| Integrations and channels | Prompts for selected optional credentials such as search, GitHub, Telegram, and Gnani when enabled. | Shows deployment settings; it does not collect credentials or activate deployment integrations. |
| Speech and embeddings | Prompts for a selected provider's missing key; other provider selection and local model setup use `.env`. | Documents OMLX, ONNX, Gnani, Jina, and OpenAI options; configure the deployment and restart Amsha. |
| Identity providers | Configure OIDC or GitHub OAuth in `.env` before startup. | Shows the server-side environment contract; it cannot change authentication settings. |
| MCP servers | Not configured by the startup wizard. | Remote HTTP/SSE only; local STDIO and Podman setup is outside onboarding. |

The CLI prepares process configuration before Quarkus starts. The web UI runs an authenticated, durable onboarding workflow after the server is running.
The web UI does not edit `.env`; its commit flow applies LLM/profile/routing and remote MCP setup. Integrations, speech, channels, identity, and runtime feature settings shown in the wizard must be configured in the deployment environment.

## Files and configuration layers

For a native distribution launched from `dist/`:

```text
dist/
├── amsha-sqlite       native executable and embedded UI assets
├── .env               deployment and process configuration
└── data/              SQLite database, vault, drafts, and bootstrap state
```

`AMSHA_DATA_DIR` selects the runtime state directory. Use a separate directory
for an isolated local test; do not delete an existing production or shared data
directory merely to make the wizard appear.

The effective LLM configuration has two main sources:

```text
process environment / .env
              │
              ├── vault priority disabled ──> runtime LLM
              │
web draft ──> encrypted vault ──commit + vault priority──> system vault ──> runtime LLM
```

`AMSHA_LLM_VAULT_PRIORITY=false` keeps environment configuration authoritative.
When it is `true`, a committed web-onboarding LLM selection can override the
environment through system-vault keys. The status and settings APIs expose only
non-secret provenance such as `ENVIRONMENT`, `VAULT`, or `DEFAULT`.

## Initial environment setup

From the repository root:

```bash
cp .env.example .env
```

Set the required security values in `.env` and keep them stable after the first
boot. The exact required set is validated by the startup CLI; at minimum, use
strong values for the vault pepper, audit HMAC key, internal API key, and auth
HMAC key. Set the provider-specific LLM key when the selected provider requires
one.

Examples:

```env
AMSHA_LLM_PROVIDER=groq
AMSHA_GROQ_API_KEY=<provider-key>
AMSHA_VAULT_PEPPER=<32+ byte random value>
AMSHA_AUDIT_HMAC_KEY=<32+ byte random value>
AMSHA_API_KEYS_INTERNAL=<strong random value>
AMSHA_AUTH_HMAC_KEY=<32+ byte random value>
AMSHA_UI_SKIP_ONBOARDING=false
AMSHA_LLM_VAULT_PRIORITY=true
AMSHA_DATA_DIR=./data
```

Provider-specific keys are canonical. Examples include
`AMSHA_GROQ_API_KEY`, `AMSHA_OPENAI_API_KEY`, and
`AMSHA_GENERIC_OPENAI_API_KEY`; new deployments should use the selected
provider's canonical key rather than the legacy generic `AMSHA_LLM_API_KEY`.
That legacy name remains only in a placeholder check, old routing error
messages, and test/Docker fixtures. Keyless providers such as Ollama and OMLX
do not need an API-key field, but their server/model configuration must be
available.

See [CONFIGURATION.md](./CONFIGURATION.md) for the complete variable reference
and [MANUAL_SETUP.md](./MANUAL_SETUP.md) for operating-system setup.

## CLI and native startup

The startup bootstrap runs before Quarkus configuration is finalized. It:

1. Finds `.env` beside the running binary (or in the current deployment
   directory).
2. Creates it from the bundled environment template when it does not exist.
3. Checks required security, provider, embedding, and optional integration
   values.
4. Prompts interactively for missing values and may update that `.env` file.
5. Creates the configured data directory and starts the requested mode.

The CLI setup is not the web wizard and does not create a durable onboarding
draft or promote web selections into the system vault.

The CLI provider menu covers the public LLM entries in
[`provider-catalog.json`](../llm-providers/src/main/resources/provider-catalog.json).
The internal `infinite-loop` router is intentionally not a direct provider
choice. Local providers such as llama.cpp do not require an API key. The CLI
requests `AMSHA_GNANI_API_KEY` only when Gnani STT is enabled or TTS is enabled
with `AMSHA_TTS_PROVIDER=gnani`; these speech features remain opt-in.

The CLI does not enable cloud speech automatically. To use Gnani transcription,
set `AMSHA_STT_GNANI_ENABLED=true`; the default language is `en-IN` and the
conservative duration limit is 30 seconds. To use Gnani speech replies, set
`AMSHA_TTS_ENABLED=true` and `AMSHA_TTS_PROVIDER=gnani`. Both adapters use
`AMSHA_GNANI_API_KEY`. Review Gnani's data terms before enabling either path.

### JVM development

```bash
./mvnw quarkus:dev -pl app -am "-Dquarkus.args=serve"
```

Use `chat` for the interactive CLI, `ask "..."` for one request, or
`attach telegram` for Telegram mode. JVM development reads the repository
configuration and `.env` according to the normal Quarkus configuration rules.

### Native distribution

Build the binary and embedded UI together:

```bash
mise run build:native-local
```

Then configure `dist/.env` and start from `dist/`:

```bash
cd dist
./amsha-sqlite serve
```

Changing UI or Java code requires rebuilding and repackaging the native binary;
editing `dist/.env` only changes runtime configuration and requires a restart.

## Web UI onboarding

Open `http://localhost:8080` after starting `serve`.

### First-owner authentication

On a fresh data directory, the UI uses the loopback-only bootstrap flow:

1. It checks `/api/v1/onboarding/bootstrap/status`.
2. An unclaimed deployment can be claimed once.
3. The claim response establishes an `HttpOnly` `AMSHA_API_KEY` cookie.
4. The UI uses that owner identity for drafts, settings, and commit operations.

Once bootstrap state is `CLAIMED`, a browser without the owner cookie receives
`401 Unauthorized`. This is expected; a hard refresh or incognito window cannot
reclaim the deployment. For local development only, enable
`AMSHA_BOOTSTRAP_LOCAL_RECOVERY=true` and use the loopback recovery action. For
a clean test, point `AMSHA_DATA_DIR` at a new isolated directory.

### Draft and commit flow

The web UI creates or resumes a durable draft and advances through this plan:

```text
welcome → connections → routes → persona-routing → mcp → integrations
→ advanced-settings → memory → security → channels → identity → review → complete
```

Drafts store non-secret selections and opaque vault references. Provider API keys and MCP headers are scrubbed before persistence and stored in the encrypted vault. Legacy drafts containing plaintext in a field named `*VaultRef` are scrubbed on read before they are returned to the browser.

The Connections step uses the same provider catalog as the CLI. Generic OpenAI connections require an absolute HTTP(S) endpoint. Ollama and OMLX are keyless local providers. For providers marked `CLI_OAUTH`, complete the provider's supported login on the Amsha host first; the web wizard cannot start the OAuth flow.

At Review, the commit flow validates and activates the selected LLM configuration and profile/routing topology. With `AMSHA_LLM_VAULT_PRIORITY=true`, provider, model, base URL, and API key values are written to system-vault keys used by runtime routing. With vault priority disabled, the environment remains active and the UI displays a warning. Remote HTTP/SSE MCP servers are also registered by the MCP step; local STDIO processes and Podman containers are not launched by onboarding.

The `advanced`, `memory`, and `security` form values are not runtime controls in the current web wizard. Integrations, channels, and identity are environment guides only; those settings are not activated by committing the draft. Configure the corresponding deployment variables and restart Amsha.

Speech and embedding choices are separate from chat-model routes. The Integrations step documents OMLX and local ONNX transcription, optional Gnani cloud STT/TTS, and Ollama/Jina/OpenAI/ONNX embeddings. Gnani uses `AMSHA_GNANI_API_KEY`; STT defaults to `en-IN` (Indian English) and a 30-second maximum. Review Gnani's data terms before enabling cloud processing.

The Channels step lists deployment configuration for Telegram and web authentication. The Identity step lists the complete OIDC and GitHub OAuth environment settings. The browser never collects these deployment credentials or edits `.env`.

Speech replies still follow each user's output-mode preference; the default remains `TEXT_ONLY`. Select an audio mode in Settings to receive generated voice replies.

## Skip and preconfigured behavior

`AMSHA_UI_SKIP_ONBOARDING=true` explicitly skips the web wizard. However, the
current backend also treats a ready effective LLM configuration as
preconfigured. Therefore `AMSHA_UI_SKIP_ONBOARDING=false` alone does not force
the wizard when the selected provider is already usable from environment or
vault configuration.

`AMSHA_LLM_PROFILE_ROUTING_ENABLED` is a separate runtime routing switch. When
false or commented, legacy persona/group/global deployment routing is used. When
true, eligible workspace or system model profiles may take precedence, with a
fallback to legacy routing when no profile route applies. It does not control
onboarding visibility, bootstrap state, vault priority, or the automatic
preconfigured check. The O1 onboarding flow activates the profile topology
(connections, model profiles, and route assignments) during draft commit or
import whenever the payload contains profiles/routes or identified connections.

This explains the Settings page message:

```text
Onboarding is pre-configured and managed externally. Reset is not available.
```

The current `skipOnboarding` value returned to the UI is an effective skip
decision: explicit skip **or** ready backend configuration. A VAULT-sourced
configuration can therefore hide the Danger Zone even when the skip variable is
commented out. The wording is broader than the actual cases; VAULT configuration
may have originated from the web wizard rather than an external operator.

The current Settings reset endpoint only discards an active onboarding draft;
it deliberately does not delete system-vault keys. For a fresh local onboarding
run, use a new `AMSHA_DATA_DIR`. Treat removal of an existing data directory as
an explicit destructive data-management operation, not a normal browser reset.

## Credential and runtime rules

- `.env` is deployment input; the web UI never modifies it.
- Environment credentials are not displayed or copied into web drafts.
- Keyless providers do not display an API-key field.
- `credentialsOk` indicates onboarding workflow completion, not the source of
  the active credential.
- Use `effectiveSource`, `credentialState`, `credentialSource`, and
  `vaultPriority` in status/settings responses to understand runtime routing.
- Changing an environment key does not replace an existing system-vault value
  while vault priority is enabled; update the active onboarding configuration or
  remove the vault value through an authorized operator path.

## CLI/API validation

For first-owner and onboarding contract checks against a running server:

```bash
TEST_BASE=http://localhost:8080 \
  bash rest-http/scripts/run_all_validations.sh --identity --onboarding
```

The identity suite expects an unclaimed bootstrap state. The onboarding suite
can use an authenticated owner key or the configured skip-onboarding path. See
[rest-http/README.md](../rest-http/README.md) and
[rest-http/AGENTS.md](../rest-http/AGENTS.md) for environment handling and
automation details.

## Related documentation

- [Quick Start](./QUICK_START.md)
- [Configuration Reference](./CONFIGURATION.md)
- [Manual Setup](./MANUAL_SETUP.md)
- [Configuration Sources and Precedence ADR](./adr/domain/ADR-Onboarding-Configuration-Source-and-Precedence.md)
- [Architecture Invariants](./Architecture_Invariants.md)
