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

After creating the App, install it on the `rsrini7` account and select **Only select repositories**;
choose `rsrini7/AmshaAgent` and no other repository. Choosing **Any account** is unnecessary for this
personal connection and would permit installation on other accounts you control.

In the GitHub App settings, generate a private key. Before uploading it, move the downloaded PEM out of
Downloads and outside the repository into a user-only directory. Replace the source filename below with
the exact filename GitHub downloaded:

```bash
mkdir -p "$HOME/.config/amsha/secrets"
chmod 700 "$HOME/.config/amsha" "$HOME/.config/amsha/secrets"
mv "$HOME/Downloads/<downloaded-file>.pem" \
  "$HOME/.config/amsha/secrets/amsha-github-app.pem"
chmod 600 "$HOME/.config/amsha/secrets/amsha-github-app.pem"
```

Set only `AMSHA_GITHUB_APP_ID` in the server `.env`; this connector does not support a
`AMSHA_GITHUB_PRIVATE_KEY_PATH` setting or inline PEM configuration. Restart Amsha to load the App ID,
then upload the PEM file's raw bytes to the internal-only credential endpoint below, using an authorized
internal API client. The endpoint stores the key in `SecretVault` and does not return key material. Do not
put PEM contents in `.env`, the browser onboarding page, shell history, or support reports. Keep the local
file protected until upload succeeds; after that, retain it only as a secure backup if needed.

For the Homepage URL, GitHub uses the address as an informational link on the App's page. The repository
URL above is appropriate while Amsha has no public product website. The Homepage URL is not a callback or
redirect URI. GitHub's [App registration guide](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app)
describes these fields and the installation choices.

Set `AMSHA_GITHUB_APP_ID` in the Amsha server's `.env`:

```dotenv
AMSHA_GITHUB_APP_ID=<numeric-app-id>
```

After installing the App as described above, upload its RSA private key through this internal-only credential endpoint:

```text
PUT /api/v1/admin/connectors/github-readme/app-credential
Content-Type: application/octet-stream
Body: raw PEM private-key bytes
```

The endpoint validates the key, stores it in `SecretVault`, and returns configuration status without returning key material. Removing the credential uses `DELETE` on the same endpoint. These credential operations require internal authorization.

### Recover the credential after a data reset

The local PEM path is only a protected backup. Amsha reads the runtime key from `SecretVault`, which is
persisted through the configured `SecretStorePort`. With the default SQLite backend, the secret record is
in `amsha-meta.db` below `AMSHA_DATA_DIR` (default `./data`). If the server was started from `dist`, that
default is normally `dist/data/amsha-meta.db`. Deleting the active data directory removes the vaulted key;
the numeric App ID in `.env` remains. PostgreSQL deployments keep the vault record in PostgreSQL, so deleting
a local data directory does not remove that database record.

Keep the PEM backup outside the data directory. Start Amsha, then re-upload it with the repository's
repeatable `mise` task. Point `--env-file` at the `.env` used by the running server:

```bash
mise run github:app-credential-upload -- \
  --key-file "$HOME/.config/amsha/secrets/amsha-github-app.pem" \
  --env-file dist/.env
```

The task reads `AMSHA_GITHUB_APP_ID` and `AMSHA_API_KEYS_INTERNAL` from that file in memory, then sends the
raw PEM bytes to the loopback-only internal endpoint. It does not put the API key or PEM contents in command
arguments or output. If the server uses another data directory or `.env` path, supply those actual paths.
After the task reports success, use **Check credential again** in the Integrations onboarding step. The UI
reports `configured`, `missing`, or `unavailable`; `unavailable` means it could not verify status and does
not claim that the key is missing. If the PEM backup is gone, generate a replacement private key in the
GitHub App settings and upload that file with the same task.

## Connect and manage the source

Connector operations require internal authorization and an active Amsha workspace where the authenticated user has the owner role.

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

Refresh uses the stored repository and path settings and resolves the current default branch again. It accepts no request body, so a refresh cannot widen the source:

```text
POST /api/v1/connectors/github-readme/refresh
```

Disconnect and remove the connection's indexed documents:

```text
DELETE /api/v1/connectors/github-readme
```

## Index and retrieval behavior

Each indexed document retains the repository, source path, default-branch commit SHA, and Git blob SHA. Matching excerpts may enter agent context with their source path and commit. Connector status and API responses do not contain document text.

A complete refresh updates eligible documents and removes paths that were deleted or are no longer eligible. Confirmed loss of repository access and owner disconnect remove the complete connection index. Temporary source or index failures preserve the last good snapshot. A change to the repository's default branch is picked up by the next successful refresh.

Prompts, tool payloads, private keys, tokens, and detected credentials must not appear in connector responses, audit records, logs, or reports.

## Verification status

The focused connector suites passed 27/27 tests on 2026-10-29, including the REST owner/workspace checks and redaction cases. The local native build also passed. A live GitHub App installation and owner-scoped refresh still require the repository owner's setup and remain unverified.
