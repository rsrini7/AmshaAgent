# Amsha Security Module (`amsha-security`)

Implementation of the [`amsha-security-api`](../security-api/README.md) contracts: secret vault, key derivation, governed outbound transport, autonomy policy, and bootstrap credential issuance. Extracted from `amsha-core` (stream S7 of the core-subsystems extraction; see [ADR-extract-core-subsystems](../Ideas-Pending/Architecture/ADR-extract-core-subsystems.md)).

This is an **impl-tier** module: it depends on `amsha-security-api` and on `amsha-core` only for audit/observability interfaces, as permitted by the two-tier boundary policy. The BouncyCastle dependency lives here, not in core.

## Components

| Type | Purpose |
|---|---|
| `SecretVaultImpl` | `SecretVault` port implementation backed by AES-GCM-SIV (`AesGcmSivCipher`) |
| `Argon2idKeyDerivation` | Argon2id-based key derivation from the system pepper |
| `VaultKeyCache` | Session-scoped derived-key cache |
| `GovernedTransportService` / `GovernedHttpClient` | Outbound HTTP governance: policy decisions, DNS revalidation, approval receipts |
| `ApprovalReceiptIssuer` / `OutboundApprovalReceipt` | Issuance and validation of HITL outbound-approval receipts |
| `StandardAutonomyPolicy` | Default `AutonomyLevel` evaluation |
| `BootstrapCredentialIssuer` / `DevSecretSeeder` | Bootstrap credentials and optional dev-secret seeding |
| `GovernedMediaDownloadService` | Implementation of `MediaDownloadPort` with streaming size limits, SSRF, and redirect revalidation |
| `GovernedTelegramMediaResolver` | Governed resolution of opaque `telegram://file/<file_id>` media references via Bot API with strict path/host validation and token scrubbing |
| `MediaSecurityValidator` | SSRF/DNS rebinding prevention, IP validation, and MIME allowlist checks |
| `GovernedTransportMetrics` | Micrometer counter and duration timer instrumentation for outbound governed HTTP calls |

## Configuration Properties

| Property | Default | Description |
|---|---|---|
| `amsha.vault.pepper` | *(none — required)* | Master pepper for Argon2id key derivation. Hard-required at startup (`CliUtils.validateRequiredEnvVars`, env `AMSHA_VAULT_PEPPER`); the `application.yaml` value is a dev-only placeholder. Never change after first boot — stored secrets become unrecoverable. Also consumed by `amsha-app`'s `StartupSecurityValidator`. See [CONFIGURATION.md](../docs/CONFIGURATION.md). |
| `amsha.auto-seed-secrets` | `false` | Seed development secrets on startup (dev convenience only). |
| `amsha.media.max-image-size-mb` | `10` | Maximum size in megabytes for single image attachments. |
| `amsha.media.max-document-size-mb` | `25` | Maximum size in megabytes for single document/audio attachments. |
| `amsha.media.max-total-payload-mb` | `50` | Maximum total payload in megabytes across all attachments in a single turn. |
| `amsha.media.connect-timeout-seconds` | `10` | Governed HTTP connect timeout in seconds for media downloads. |
| `amsha.media.read-timeout-seconds` | `30` | Governed HTTP read timeout in seconds for media downloads. |
| `amsha.channel.telegram.bot-token` | `""` | Telegram Bot token for resolving and downloading `telegram://` media. |
| `amsha.channel.telegram.base-uri` | `https://api.telegram.org` | Telegram Bot API base URI for `getFile` and file downloads. |

## Media Download Telemetry & Metrics

The governed media download boundary emits payload-free Micrometer metrics under `amsha.media.download.*` with bounded categorical tags:

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `amsha.media.download.total` | Counter | `source`, `type`, `status` | Total download attempts categorized by status (`success`, `policy_denied`, `hitl_required`, `timeout`, `redirect_denied`, `security_violation`, `oversized`, `unsupported_mime`, `magic_mismatch`, `error`). |
| `amsha.media.download.duration` | Timer | `source`, `type`, `status` | Latency distribution of media download operations. |
| `amsha.media.download.bytes` | Counter | `source`, `type` | Cumulative bytes successfully downloaded through the governed boundary. |

## Outbound HTTP Transport Telemetry & Metrics

All outbound HTTP calls routed through `GovernedTransportService` (including LLM reasoning calls and Model Arena comparisons via `GovernedLangChainHttpClient`) emit payload-free Micrometer metrics:

| Metric Name | Type | Tags | Description |
|---|---|---|---|
| `amsha.transport.requests.total` | Counter | `purpose`, `status` | Total outbound HTTP requests categorized by purpose (`provider_api_call`, `media_download`) and status (`success`, `ssrf_denied`, `credential_denied`, `policy_denied`, `timeout`, `error`). |
| `amsha.transport.duration` | Timer | `purpose`, `status` | Latency distribution of outbound HTTP requests. |

## Downstream Usage

```xml
<dependency>
  <groupId>com.amsha</groupId>
  <artifactId>amsha-security</artifactId>
</dependency>
```
