# Amsha Security API Module (`amsha-security-api`)

Pure API contracts for security in Amsha Agent: risk vocabulary, autonomy levels, outbound policy decisions, and the secret-vault port. Extracted from `amsha-core` (stream S4 of the core-subsystems extraction; see [ADR-extract-core-subsystems](../Ideas-Pending/Architecture/ADR-extract-core-subsystems.md)).

`amsha-security-api` contains **zero dependencies on `amsha-core`**, so consumers (`amsha-sandbox`, `amsha-tools-builtin`, `amsha-llm-providers`, `amsha-mcp`, `amsha-onboarding`) reference security contracts without importing core internals. Implementations live in [`amsha-security`](../security/README.md).

## Core Contracts

| Type | Purpose |
|---|---|
| `RiskLevel` | Canonical risk classification shared by guards, approvals, and classifiers |
| `ToolOperation` | Operation taxonomy (read/compute/network/shell-like) driving HITL rules |
| `AutonomyLevel` | Declared agent autonomy tier evaluated by the autonomy policy |
| `OutboundPolicyDecision` / `OutboundPurpose` | Governed-egress decision record and purpose classification |
| `SecretVault` | Port for storing and retrieving secrets (implemented by `SecretVaultImpl` in `amsha-security`) |
| `VaultKey` / `VaultConstants` | Vault key identifiers and shared constants |
| `CryptoConstants` | Cryptographic constants shared across modules |
| `RequiresSecret` / `RequiresSecrets` | Declarations that a capability requires named vault secrets |
| `MediaDownloadPort` | Port contract for governed, safety-validated media downloads |
| `DownloadedMedia` | Container for downloaded media content with deterministic temp file cleanup |
| `MediaBudgetLimits` | Media size limits, timeouts, and MIME allowlist constants |

## Downstream Usage

```xml
<dependency>
  <groupId>com.amsha</groupId>
  <artifactId>amsha-security-api</artifactId>
</dependency>
```
