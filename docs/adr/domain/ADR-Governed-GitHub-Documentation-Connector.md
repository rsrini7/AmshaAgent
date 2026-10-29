# ADR: Governed GitHub Documentation Connector

- **Status**: **Accepted** (2026-10-29), amended for engineering-document scope; live installation verification remains pending.
- **Decision owner:** Amsha Agent repository owner
- **Feature slug:** `agent-stack-gap-closure`
- **Related decision:** [Identity and workspace access](ADR-Identity-Access-and-Workspace-Sharing.md)

## Context

Issue 03 needs a useful external documentation source to prove Amsha's governed connector path. The initial profile README source established a narrow technical test but offered little ongoing retrieval value. The owner selected the engineering repository `rsrini7/AmshaAgent` instead.

The existing GitHub agent tool performs on-demand queries. It does not maintain a durable, owner-scoped index, reconcile changed or deleted files, or bind retrieval to a persisted connection manifest. The connector adds those lifecycle and workspace guarantees while retaining the tool for ad-hoc GitHub queries.

## Decision

Store repository identity and the path allowlist as settings in each connection manifest. The first connection is restricted to:

- Repository: `rsrini7/AmshaAgent`
- Ref: the repository's current default branch; resolve and record its commit SHA on each refresh.
- Paths: root `README.md`, plus Markdown files under `docs/` ending in `.md`.

The first-source policy rejects any different repository or broader path pattern. Exclude source code, configuration, binary, hidden, generated, symlink, submodule, unsupported-extension, oversized, and credential-bearing documents. Use the existing `CredentialDetector` to reject credential-bearing content. Never include document content in status, audit, logs, errors, or reports.

Install the GitHub App only on `rsrini7/AmshaAgent`, granting read-only Contents access and the required read-only repository metadata permission. Keep the private key in `SecretVault` and only the App ID in configuration. Validate the selected-repository installation and permission scope before minting a repository-scoped installation token. Send every GitHub request through the governed outbound transport with audit before egress.

Connect persists the approved repository and path settings. Refresh reads those stored settings, resolves the current default branch to a commit, and enumerates the tree at that commit. Fetch only eligible Markdown blobs at the resolved commit. Each indexed document retains the repository, path, commit SHA, and blob SHA. The refresh caller cannot override the branch or paths.

A complete refresh upserts eligible documents idempotently and removes documents absent from the complete, eligible tree. A truncated, malformed, or otherwise incomplete response preserves the last known-good index. Confirmed repository access revocation and owner disconnect remove the complete connection index. Transient transport, rate-limit, or server failures preserve indexed content.

Require internal authorization and active-workspace owner membership before egress or mutation. Derive the archival namespace from both the authenticated owner and the workspace where they connect. Retrieve excerpts only for that owner in that workspace, and include a safe path and commit reference in matching context. Reuse the selected persistence backend's `ArchivalMemoryStorePort`.

## Runtime opt-in

The feature is disabled by default through `AMSHA_GITHUB_DOCS_CONNECTOR_ENABLED=false`. Enabling it is an explicit server configuration change followed by a restart. Disabled mode blocks connect and refresh, reports `DISABLED`, and prevents prompt retrieval from reading the index. Owners may still disconnect to remove existing indexed documents. Credential upload and status remain available so an operator can prepare the integration without activating it.

## Considered options

- **Keep only the profile README:** rejected because it has little practical value for engineering knowledge retrieval.
- **Index the whole repository or accept arbitrary paths:** rejected because that exceeds the owner's source boundary and increases retained data and exposure.
- **Use the existing GitHub CLI tool for indexing:** rejected because on-demand queries do not provide a durable connection manifest, reconciliation, or scoped deletion lifecycle.
- **Share a workspace-wide index:** rejected because retrieval is owner-only, even inside the connecting workspace.
- **Use a personal access token or OAuth user token:** rejected in favor of a repository-selected GitHub App with read-only Contents access.
- **Delete indexed content on every refresh error:** rejected because transient or incomplete reads must preserve known-good documents.

## Consequences

The connector provides searchable engineering documentation while preserving least privilege. Repository identity and allowed paths are connection data, while the first-source validator enforces exactly `rsrini7/AmshaAgent`, root `README.md`, and Markdown under `docs/`. Supporting another repository, file type, or path requires an explicit scope decision and policy change; the settings model does not let a caller widen the first connection.

Issue 03 remains open until the revised implementation passes focused checks and a live owner-scoped refresh. The live check requires an App ID, a validated private key in `SecretVault`, and an installation restricted to the selected repository. No live installation or refresh is claimed here.

Issue 06 platform and release gates remain separate. The parent Jev decision-plane ADR remains **Deferred**; this connector does not activate classifiers or register a production `DecisionEngine`.

## Implementation checkpoint (2026-10-29)

The profile-README implementation has been adapted to persisted connection settings, bounded multi-document snapshots, default-branch commit resolution, per-document path/commit/blob provenance, and idempotent reconciliation. Real REST integration tests cover active-workspace owner enforcement, cross-workspace denial, deletion, revocation, transient failures, and redaction.

On JDK 25.0.3, six focused connector suites passed 27/27 tests, with 0 failures, 0 errors, and 0 skips. The app reactor PMD gate, FQN cleanup (0 files modified), Spotless apply/check, and documentation verification passed. The local native build passed with `mise run build:native-local` and assembled `dist/amsha-sqlite`; existing `dist/data/` was preserved. The first sandboxed REST attempt could not bind its loopback fixture; the same focused command passed with loopback access enabled.

The owner previously deferred the live App installation and refresh. Issue 03 is therefore `ready-for-human`, not resolved, until that owner-scoped refresh verifies the source paths and current default-branch commit.
