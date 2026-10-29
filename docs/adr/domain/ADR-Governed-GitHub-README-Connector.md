# ADR: Governed GitHub README Connector

- **Status**: **Accepted** (2026-10-29) — source and access contract confirmed by the repository owner; live installation verification is deferred.
- **Date**: 2026-10-29
- **Decision Owners**: Amsha Agent repository owner
- **Feature Slug**: `agent-stack-gap-closure`
- **Related Decision**: [Identity and workspace access](ADR-Identity-Access-and-Workspace-Sharing.md)

## Context

Issue 03 needs one governed external source to validate Amsha's connector path. The owner selected their public profile repository, `rsrini7/rsrini7`, whose root README is the intended source. The decision must preserve owner-only search scope even though the GitHub source is public, and must restrict what the App can read.

## Decision

The connector reads only the public root `README.md` from `rsrini7/rsrini7`. It resolves the repository's current default-branch tip to a commit SHA, then fetches that exact root path at the resolved commit. Repository and path are fixed by the connector; callers cannot choose another repository, branch, or file. The GitHub App is installed only on this repository and receives read-only Contents access, with repository metadata read only as required to verify installation and source identity.

Each connection and index belongs to the authenticated Amsha user and the active workspace in which they connect it. Index identifiers derive from both owner and workspace. Only that user in that workspace may retrieve README excerpts through agent context. The connector API requires internal access and active-workspace owner membership; status responses and audit events expose metadata, never README contents. The connector reuses the existing `ArchivalMemoryStorePort` and its configured persistence backend.

The non-secret App ID is configuration. The App private key is stored in `SecretVault` through the audited internal credential endpoint. Connect and refresh are explicit owner-authorized operations. Every GitHub HTTP request uses the governed outbound client and is audited before egress. README text may be used as matching owner/workspace context for search, but must not appear in connector responses, audit details, logs, or reports; prompts, tool payloads, and credentials are likewise excluded from those surfaces.

Refresh replaces the prior indexed version idempotently. Confirmed README deletion, revoked repository access, or owner disconnect removes the scoped index. A transient transport, rate-limit, or server failure preserves the last known good index.

## Considered Options

- **Read the whole repository or accept arbitrary paths** — rejected because this exceeds the owner's requested source boundary and increases the amount of data retained and searched.
- **Share one workspace-wide index** — rejected because the owner requested search access only for themselves, even inside the connecting workspace.
- **Use a personal access token or OAuth user token** — rejected in favor of a selected-repository GitHub App installation with read-only Contents access and a SecretVault-held App key.
- **Delete indexed data on any refresh error** — rejected because transient failures must not erase known-good content; deletion is reserved for confirmed removal, revoked access, or disconnect.

## Consequences

The App ID, private key, and selected-repository installation must be configured before a live connection can be exercised. The owner deferred that live test. Issue 03 is therefore `ready-for-human`, with live connect/refresh as its only open acceptance gate; this ADR accepts the design and does not claim that GitHub installation or retrieval has been verified.

Changes to the source repository, permitted paths, App installation scope, or owner/workspace visibility require revisiting this decision. Issue 06's Linux and Windows runtime checks, native golden corpus, and signed provenance remain separate release evidence. The Jev decision-plane ADR remains **Deferred**.

## Implementation Checkpoint

The connector implementation is complete. Focused verification passed 28 tests with 0 failures, 0 errors, and 0 skips; app reactor compile and PMD, Spotless, and documentation validation passed. The required live GitHub installation and owner-scoped refresh remain deferred. Repository clean-install and Sonar issue checks remain pre-commit gates and are not claimed as passed here.
