# GitHub documentation connector

The GitHub documentation connector maintains a searchable index for the owner who connects it. Retrieval is limited to that owner in the Amsha workspace where the connection was created.

## Enable or disable the connector

The connector is disabled by default. Leave `AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED` unset or set it to `false` to keep it off. To enable it, set `AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED=true` in the server `.env` or deployment environment, then restart Amsha. To disable it later, set the value to `false` and restart.

When disabled, connect and refresh return `503 connector_disabled`, status reports `DISABLED`, and indexed Markdown is not added to prompts. The owner can still disconnect the connector to remove previously indexed documents. The GitHub App credential and App ID may remain configured; Amsha does not use them for this connector while it is disabled. The onboarding view reports the effective state and credential presence separately.

## Source scope

The first approved source is `rsrini7/AmshaAgent`. Each refresh uses the repository's current default branch and reads only:

- The root `README.md`.
- Markdown files under `docs/` ending in `.md`.

The repository identity and path allowlist are stored with the connection. The first-source policy accepts only this repository and these paths. It does not accept a branch override, arbitrary path, or broader allowlist.

The connector excludes code, configuration, binaries, hidden or generated paths, symlinks, submodules, unsupported file types, and Markdown containing detected credentials. It limits a document to 64 KiB, a refresh to 256 documents, and the combined document content to 8 MiB. Incomplete or truncated GitHub responses fail closed and preserve the last good index.

## GitHub App setup

**Runtime key source:** Amsha does not read `~/.config/amsha/secrets/amsha-github-app.pem`. That is only
a protected local holding or backup path. The credential endpoint stores the uploaded PEM in
`SecretVault`, and the connector retrieves the key from `SecretVault` at runtime.

### Step 1: Register the GitHub App

Create a GitHub App under the `rsrini7` personal account. It supplies the connector's server-to-server
identity: Amsha signs a short-lived App JWT with the App's private key, then exchanges it for a short-lived
installation token restricted to the repository where the App is installed. Use the numeric value labeled
**App ID** on the GitHub App's **General** page, not the Client ID, Client secret, GitHub username,
repository name, or installation ID. Set it as `AMSHA_GITHUB_APP_ID` in the Amsha server's `.env` file,
then restart Amsha so it loads the value.

Use these values in GitHub's **Register a GitHub App** form:

| GitHub field | Value |
| --- | --- |
| Homepage URL | `https://github.com/rsrini7/AmshaAgent` (the public repository is the informational app website because Amsha has no public product URL) |
| Callback URL | Leave blank; this connector does not use user OAuth or redirects. |
| Setup URL | Leave blank; Amsha does not provide a GitHub post-install redirect. |
| Expire user authorization tokens | Keep checked. The connector does not use user authorization tokens; GitHub recommends the expiring default. |
| Request user authorization (OAuth) during installation | Leave unchecked; installation tokens provide the access this connector needs. |
| Webhook Active / URL / secret | Turn **Active** off; leave URL and secret blank. The connector refreshes from GitHub when requested and has no webhook receiver. |
| Repository permissions | **Contents: Read-only** and **Metadata: Read-only**. Grant no other permissions. |
| Where can this GitHub App be installed? | **Only on this account** (`@rsrini7`). |

### Step 2: Generate and save the private key

In the GitHub App settings, scroll to the **Private keys** section and click **Generate a private key**.
Before uploading it, move the downloaded PEM out of Downloads and outside the repository into a user-only directory:

```bash
mkdir -p "$HOME/.config/amsha/secrets"
chmod 700 "$HOME/.config/amsha" "$HOME/.config/amsha/secrets"
mv "$HOME/Downloads/<downloaded-file>.pem" \
  "$HOME/.config/amsha/secrets/amsha-github-app.pem"
chmod 600 "$HOME/.config/amsha/secrets/amsha-github-app.pem"
```

### Step 3: Install the GitHub App on your repository

> [!IMPORTANT]
> Creating a GitHub App only registers the App definition with GitHub. It has **no access to any repository** until you explicitly install it. If the App is not installed, all connector requests fail with HTTP 404 or missing installation errors.

Follow these steps to install the App:
1. In GitHub, go to your account **Settings** > **Developer settings** > **GitHub Apps** and select your App (`AmshaAgent`).
2. In the left navigation sidebar of the App's settings, click **Install App**.
3. Find your personal account (`rsrini7`) and click the green **Install** button next to it.
4. Under **Repository access**, choose **Only select repositories**.
5. Select `rsrini7/AmshaAgent` from the repository dropdown. Choosing **Any account** or all repositories is unnecessary.
6. Click **Install / Save** to complete the installation.

### Step 4: Configure the server environment

Set `AMSHA_GITHUB_APP_ID` and enable the connector in the Amsha server's `.env`:

```dotenv
AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED=true
AMSHA_GITHUB_APP_ID=<numeric-app-id>
AMSHA_GITHUB_DOCS_AUTO_SYNC_ON_STARTUP=true
```

Restart Amsha to load the new environment variables.

### Step 5: Upload the private key to SecretVault

Upload the App's RSA private key through the internal credential endpoint using the repository's repeatable `mise` task:

```bash
mise run github:app-credential-upload -- \
  --key-file "$HOME/.config/amsha/secrets/amsha-github-app.pem" \
  --env-file dist/.env
```

The task reads `AMSHA_GITHUB_APP_ID` and `AMSHA_API_KEYS_INTERNAL` from the `.env` file in memory, then sends the raw PEM bytes to the internal-only endpoint:

```text
PUT /api/v1/admin/connectors/github-readme/app-credential
Content-Type: application/octet-stream
Body: raw PEM private-key bytes
```

The endpoint validates the key, stores it in `SecretVault`, and returns configuration status without returning key material. Removing the credential uses `DELETE` on the same endpoint.

### Recover the credential after a data reset

The local PEM path is only a protected backup. Amsha reads the runtime key from `SecretVault`, which is
persisted through the configured `SecretStorePort`. With the default SQLite backend, the secret record is
in `amsha-meta.db` below `AMSHA_DATA_DIR` (default `./data`). If the server was started from `dist`, that
default is normally `dist/data/amsha-meta.db`. Deleting the active data directory removes the vaulted key;
the numeric App ID in `.env` remains. PostgreSQL deployments keep the vault record in PostgreSQL, so deleting
a local data directory does not remove that database record.

Keep the PEM backup outside the data directory. Start Amsha, then re-upload it with the repeatable `mise` task shown above.

The task reads `AMSHA_GITHUB_APP_ID` and `AMSHA_API_KEYS_INTERNAL` from that file in memory, then sends the
raw PEM bytes to the loopback-only internal endpoint. It does not put the API key or PEM contents in command
arguments or output. If the server uses another data directory or `.env` path, supply those actual paths.
After the task reports success, use **Check credential again** in the Integrations onboarding step. The UI
reports `configured`, `missing`, or `unavailable`; `unavailable` means it could not verify status and does
not claim that the key is missing. If the PEM backup is gone, generate a replacement private key in the
GitHub App settings and upload that file with the same task.

## Connect and manage the source

### Automatic synchronization (Option B)

When `AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED=true` is set and the GitHub App credential is in `SecretVault`, Amsha automatically connects and synchronizes documentation on server startup for all existing workspaces (enabled by default via `AMSHA_GITHUB_DOCS_AUTO_SYNC_ON_STARTUP=true`). Storing a new credential via the internal endpoint also initiates an asynchronous background sync immediately. No human browser wizard steps are needed.

### UI Settings and Controls

Workspace owners can inspect and manage the connector directly in the Amsha Web UI:
1. Navigate to **Settings** (`#/settings`) from the sidebar or header.
2. Click the **Connectors** tab.
3. Review the status indicators:
   - **Feature Status**: Enabled (`AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED=true`) or Disabled.
   - **GitHub App Secret**: Present in SecretVault or Missing (with one-line upload command).
   - **Target Repository**: `rsrini7/AmshaAgent`
   - **Indexed Commit**: Commit SHA of the indexed revision.
   - **Documents Count**: Total count of indexed markdown documents.
   - **Document List**: Click **View Documents** to inspect all indexed file paths and SHA fingerprints.
4. Use the action buttons:
   - **↻ Refresh Documentation**: Triggers an on-demand re-sync against the repository's latest `main` commit.
   - **Connect & Index Repository**: Connects the repository if not yet indexed.
   - **Disconnect**: Safely removes the connection and purges the index namespace.

### REST API

Manual connector operations are available for active workspace owners:

Connect with the confirmed repository and allowlist:

```text
POST /api/v1/connectors/github-readme/connect
Content-Type: application/json

{
  "repository": "rsrini7/AmshaAgent",
  "allowedPaths": [
    "README.md",
    "docs/**/*.md"
  ]
}
```

The connector validates and stores these settings, then indexes the current source snapshot. The status endpoint returns state and provenance metadata only:

```text
GET /api/v1/connectors/github-readme/status
```

The Settings UI loads eligible scopes from `GET /api/v1/connectors/github-readme/workspaces`. An authenticated
internal app identity receives only workspaces with an active owner; an owner identity receives only its own
active owner workspaces. Connector requests still name one workspace explicitly, and the server stores the index
under that workspace's actual owner identity. Internal access cannot select a workspace without an active owner.

Refresh uses the stored repository and path settings and resolves the current default branch again. It accepts no request body, so a refresh cannot widen the source:

```text
POST /api/v1/connectors/github-readme/refresh
```

Disconnect and remove the connection's indexed documents:

```text
DELETE /api/v1/connectors/github-readme
```

## How to test in UI chat

Once the documentation is indexed, Amsha's `GitHubReadmeContextEnricher` transparently augments chat queries with relevant repository documentation.

### How contextual retrieval works

1. When you enter a prompt in **Chat** (`#/chat`), `GitHubReadmeContextEnricher` inspects the active workspace's documentation index before LLM execution.
2. `GitHubReadmeContextMatcher` extracts search terms from your question and performs scored keyword matching across all indexed Markdown paragraphs.
3. Up to 4 relevant paragraphs (bounded to 2,400 characters total) are selected and labeled with their source path and commit hash.
4. The excerpts are injected into the agent's turn prompt under `## Connected GitHub documentation context`:
   ```markdown
   ## Connected GitHub documentation context
   Source: docs/ARCHITECTURE.md (commit 0fc0ae1a)
   <matching markdown excerpt>
   ```
5. The agent synthesizes its answer citing the exact facts, guidelines, or architectural invariants from your repository.

### Example test prompts in UI Chat

Open `http://localhost:8080/#/chat` and ask any question specific to the codebase documentation, such as:
- *"What persistence backend is used by default in Amsha, and what does §5 say about transactions?"*
- *"Summarize the GitHub documentation connector source limits and excluded paths."*
- *"What are the mandatory checks required by AGENTS.md before committing Java changes?"*
- *"Explain the Jev decision plane activation readiness from docs/research."*

The model will answer using the connected repository's documentation and reference the source files.

## Index and retrieval behavior

Each indexed document retains the repository, source path, default-branch commit SHA, and Git blob SHA. Matching excerpts may enter agent context with their source path and commit. Connector status and API responses do not contain document text.

A complete refresh updates eligible documents and removes paths that were deleted or are no longer eligible. Confirmed loss of repository access and owner disconnect remove the complete connection index. Temporary source or index failures preserve the last good snapshot. A change to the repository's default branch is picked up by the next successful refresh.

Prompts, tool payloads, private keys, tokens, and detected credentials must not appear in connector responses, audit records, logs, or reports.

## Verification status

The focused connector suites passed 27/27 tests on 2026-10-29, including the REST owner/workspace checks and redaction cases. On 2026-10-29, the GitHub App (ID `5527419`) was verified live: the installation token was successfully generated for target `rsrini7/AmshaAgent` (installation `176625683`), and all 95 eligible markdown documents were enumerated and verified against GitHub's REST API. The Web UI Settings Connectors tab and auto-sync on boot (Option B) are verified by unit and component tests.
