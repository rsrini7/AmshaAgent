# Onboarding Guide

This guide explains the two setup paths in Amsha Agent, what each path changes,
where state is stored, and how configuration becomes active at runtime.

## Choose a setup path

| Path | Best for | Writes `.env` | Stores durable onboarding state |
|---|---|---:|---:|
| CLI / native startup | Headless setup, operators, local development | Yes, when values are missing | No web draft |
| Web UI | Interactive provider, routing, MCP, channel, and feature setup | No | Yes, under `AMSHA_DATA_DIR` |

The CLI prepares process configuration before Quarkus starts. The web UI runs the
authenticated, durable onboarding workflow after the server is running. The web
UI does not edit `.env`.

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
AMSHA_UI_SKIP_ONBOARDING=false
AMSHA_LLM_VAULT_PRIORITY=true
AMSHA_DATA_DIR=./data
```

Provider-specific keys are canonical. Examples include
`AMSHA_GROQ_API_KEY`, `AMSHA_OPENAI_API_KEY`, and
`AMSHA_GENERIC_OPENAI_API_KEY`; do not use the legacy generic
`AMSHA_LLM_API_KEY` for new deployments. Keyless providers such as Ollama and
OMLX do not need an API-key field, but their server/model configuration must be
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

### JVM development

```bash
./mvnw quarkus:dev -pl amsha-app -am "-Dquarkus.args=serve"
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

The UI creates or resumes a durable draft and advances through this plan:

```text
welcome → connections → routes → persona-routing → mcp → integrations
→ advanced-settings → memory → security → channels → identity → review → complete
```

The draft stores non-secret selections and opaque references. Provider API keys,
MCP headers, and other credentials are scrubbed before persistence and stored in
the encrypted vault. The UI never receives environment credentials back as
plaintext.

The connections catalog controls whether a provider needs an API-key field.
Generic OpenAI connections require a valid absolute HTTP(S) endpoint. Ollama
and OMLX are keyless when configured as local providers.

Claude Code is also keyless in the wizard and uses `CLI_OAUTH`: runtime
readiness comes from the system-vault Claude OAuth token or the local Claude
Code credentials file (`~/.claude/credentials.json`). Status exposes only
configured/missing credential state; it never returns the token.

At Review, the selected LLM configuration is validated and the commit saga
activates it. With vault priority enabled, provider, model, base URL, and API
key values are written to system-vault keys used by runtime routing. With vault
priority disabled, the commit can succeed but the environment remains active;
the UI displays this warning.

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
preconfigured check. The current O1 onboarding flow also does not create the
final profile-route records required for that feature.

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
- [Configuration Sources and Precedence ADR](../Ideas-Pending/ADR-Onboarding-Configuration-Source-and-Precedence.md)
- [Architecture Invariants](./Architecture_Invariants.md)
