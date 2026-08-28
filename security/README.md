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
| `MediaSecurityValidator` | SSRF/DNS rebinding prevention, IP validation, and MIME allowlist checks |

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

## Downstream Usage

```xml
<dependency>
  <groupId>com.amsha</groupId>
  <artifactId>amsha-security</artifactId>
</dependency>
```
