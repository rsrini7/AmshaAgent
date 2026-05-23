### Regarding `build.env`

You don't need to worry about `build.env`-it is an optional feature built into the `mise.toml` tasks, not a requirement. 

Here is why that logic exists in the build scripts:
1. **The Problem:** The native compiler (GraalVM) embeds any active environment variables it sees at compile time directly into the binary's memory. This is highly dangerous because your `.env` contains your API keys (`ARCCLAW_LLM_API_KEY`, etc.), meaning your secrets would get permanently baked into the executable and leaked to anyone you share the binary with.
2. **The Shield:** To prevent this, the `build:native` tasks explicitly rename/hide your `.env` to `.env.build-backup` while compilation is happening.
3. **The Fallback:** Sometimes developers *want* to embed non-secret config. If a file named `build.env` exists, the script temporarily loads it instead of your `.env`.

If you never created a `build.env`, the build simply runs completely "clean" with no external environment variables, which is exactly what we want. 

Now that we have explicitly added **`-Dquarkus.profile=prod`** directly to the Maven command line in the `mise.toml` scripts, the build is completely bulletproof. The GraalVM compiler is explicitly forced into the `prod` profile regardless of what hidden files do or don't exist in the environment!


---------------

One more question 

DevSecretSeeder.java
 only loads .env when profile is dev , why the graalvm binary is generating .env file with prod profile and it still works.. how.. we have 
SecretVaultImpl.java
 but not sure when it was used , ideally prod profile right ? clarify first fully

Viewed DevSecretSeeder.java:1-188

Here is exactly how the configuration and secret architecture works in ArcClaw. It's a common area of confusion, so let's break down the boundaries between `.env`, `AppBootstrap`, and the `SecretVault`.

### 1. Why the Native Binary Uses `.env` (and why it works in `prod`)
The **`.env` file is loaded in ALL profiles** (`dev`, `test`, `prod`), not just `dev`. 

When you run the native binary (`prod`), the very first thing that executes-even before Quarkus boots up-is **`AppBootstrap.loadDotEnv()`**. 
* It reads your `.env` file.
* It injects every value directly into Java's `System.setProperty()`.
* When Quarkus (and LangChain4j) finally boots up a microsecond later, it natively reads from `System.getProperty()`. 

That is why `ARCCLAW_LLM_API_KEY` "just works" in the native binary. It relies on standard system configuration, **not** the `SecretVault`.

### 2. What is `SecretVaultImpl` used for?
The `SecretVault` is **not** used to store the core application configuration (like the LLM API key, database path, or server ports). 

The vault is an **encrypted database table** specifically designed for **Agent-specific Tool Secrets** (like `GITHUB_TOKEN`, `ARCCLAW_TOOLS_SERPER_API_KEY`, etc.). 

If you are running ArcClaw as a real production server with multiple users:
* You **would never** put a single global `GITHUB_TOKEN` in the `.env` file.
* Instead, User A and User B would securely upload their own API keys via the REST API (`/api/v1/vault`).
* Those keys are stored securely in the `SecretVault`, encrypted at rest using the `ARCCLAW_VAULT_PEPPER`.
* When the Coder agent spins up for User A, it reads User A's `GITHUB_TOKEN` from the vault.

### 3. What does `DevSecretSeeder` actually do?
Since having to manually upload your GitHub or Serper API keys via a REST API every time you wipe your local database is incredibly annoying for developers, we created `DevSecretSeeder`.

If `DevSecretSeeder` is active, it reads the tool keys (`GITHUB_TOKEN`, `ARCCLAW_TOOLS_SERPER_API_KEY`) out of your `.env` file and **automatically injects them into the `SecretVault`** on boot.

**You asked why it only works in `dev`?**
Looking closely at the code in `DevSecretSeeder`, it actually **can** run in `prod`:
```java
if (autoSeedEnabled) {
    seedDevSecrets(); // seeds GitHub token, etc. into the vault
}
```
If you look at the `.env.example`, we set `ARCCLAW_AUTO_SEED_SECRETS=true` (and I just updated `AppBootstrap` to generate your `.env` with `true` by default).

Because `ARCCLAW_AUTO_SEED_SECRETS` is `true`, `DevSecretSeeder` **will run in your native `prod` binary**, automatically making your single-user CLI experience completely seamless by moving any search/tool keys from your `.env` right into the encrypted vault on startup! 

### Summary
* **`AppBootstrap` (.env)**: Powers the framework (LLM connections, ports, database paths). Runs in `dev` and `prod`.
* **`SecretVault`**: Powers the agent tools (GitHub, Serper). Required for production multi-tenant security.
* **`DevSecretSeeder`**: A bridge that copies tool keys from `.env` to `SecretVault` so single-user CLI users don't have to manually provision their vault. Runs in `dev`, and runs in `prod` only if `ARCCLAW_AUTO_SEED_SECRETS=true`.

---

But we have 
AppBootstrap.java
right why cant we use that to load directly SecretVault instead of create .env when the profile is prod and obviously binary file in any os ?

That is an excellent architectural question! The short answer is the **"Chicken and Egg" problem of database connections and Quarkus startup.**

Here is the exact timeline of what happens the millisecond you run `arcclaw.exe` (or the Linux binary):

### Phase 1: Pre-Boot (`AppBootstrap.java`)
1. You run `arcclaw.exe`.
2. Java hits `public static void main()`.
3. `AppBootstrap.bootstrap()` runs immediately.
   * **At this exact moment, there is no database.** The database connection has not been established. Dependency Injection (CDI) does not exist. The application doesn't even know what folder the SQLite database is stored in!
   * If we tried to call `SecretVaultImpl` right now, the app would instantly crash because `SecretVault` needs a live database connection to read/write its encrypted tables.
4. **This is why `.env` must exist.** `AppBootstrap` reads the `.env` file to inject the foundational configuration (like `ARCCLAW_DATA_DIR` to find the database, and `ARCCLAW_VAULT_PEPPER` to know how to decrypt the vault) into the system properties so Quarkus can see them.

### Phase 2: Quarkus Boot
5. Quarkus takes over. It reads the system properties that `AppBootstrap` just set up.
6. It finds `ARCCLAW_DATA_DIR`, spins up the connection pool, and connects to the SQLite database.
7. It spins up the Dependency Injection container and instantiates all your beans (`@ApplicationScoped`, etc).

### Phase 3: Post-Boot (`DevSecretSeeder`)
8. Now that the database is fully online and `SecretVaultImpl` is safely injected, Quarkus fires a `StartupEvent`.
9. `DevSecretSeeder` listens for this event. Since the database is finally awake, the seeder can safely grab the tool keys (`GITHUB_TOKEN`, etc.) from the environment and save them into the `SecretVault` table for your agents to use.

### Summary
We **must** use the `.env` file because `AppBootstrap` needs a simple text file to figure out *how* to turn on the database. 

We can only talk to `SecretVault` *after* the database is fully online, which happens long after `AppBootstrap` has finished its job.

---

ok if we set ARCCLAW_AUTO_SEED_SECRETS as false how the prod profile works ?

If you set `ARCCLAW_AUTO_SEED_SECRETS=false` in the `prod` profile, here is exactly what happens:

### 1. Basic Chat & Reasoning Still Works
`AppBootstrap` still loads the `.env` file into system properties. Quarkus still successfully connects to the LLM (using `ARCCLAW_LLM_API_KEY`) and connects to your local SQLite database. You can chat with the agent and it can reason perfectly fine.

### 2. Tool Agents Will Be Blind
If the Coder agent tries to search a repository, or the Researcher tries to run a Google search via Serper, they will **immediately fail** and return an error like: *"Missing credential GITHUB_TOKEN"*. 

Because `DevSecretSeeder` was turned off, the `SecretVault` Starts completely empty. It did not copy your `GITHUB_TOKEN` or `ARCCLAW_TOOLS_SERPER_API_KEY` from your `.env` file.

### 3. How do you fix it (in true Prod)?
In a true multi-tenant production environment (e.g., hosted on a cloud server for 100 different users), you don't *want* a global `GITHUB_TOKEN` in a `.env` file. 

Instead, User A would log into the Web UI, go to their settings, and paste their personal GitHub Token. The Web UI would send an HTTP request to ArcClaw:

```http
POST /api/v1/vault/keys
Authorization: Bearer <internal-api-key>

{
    "keyName": "GITHUB_TOKEN",
    "secretValue": "ghp_xxxxxxxx"
}
```
ArcClaw receives that request, encrypts User A's token with the `ARCCLAW_VAULT_PEPPER`, and saves it into the SQLite table. From then on, only User A's agent can use that token.

### Why `true` is the default for you
Because ArcClaw is primarily meant to be a single-user desktop agent across Windows/WSL2, forcing you to use `curl` commands or a UI settings page to upload your own API keys to your own database every time you wipe it would be exhausting. 

Setting `ARCCLAW_AUTO_SEED_SECRETS=true` is simply a quality-of-life feature that says: *"I am the only user of this server; just copy my `.env` tools into the secure vault for me so I don't have to."*

=----

now i have question, can we delete all entries of secrets whatever from .env file and push all the secrets into db once quarkus boots ? this will reduce accidental data leak right ? any other thought ? still not clear full purpose.

This is a great security question. Let me give you the complete picture with clear boundaries.

## The Two-Tier Secret Architecture

```
┌─────────────────────────────────────────────────────────┐
│  TIER 1 - Bootstrap Secrets (.env, MUST stay)           │
│  Read by AppBootstrap BEFORE Quarkus/DB exists          │
│                                                         │
│  ARCCLAW_VAULT_PEPPER   ← master key for vault itself   │
│  ARCCLAW_LLM_API_KEY    ← LangChain4J reads at boot     │
│  ARCCLAW_DATA_DIR       ← where is the SQLite DB?       │
│  ARCCLAW_AUDIT_HMAC_KEY ← audit chain integrity         │
│  QUARKUS_PROFILE        ← which profile to boot         │
└─────────────────────────────────────────────────────────┘
                        ↓ Quarkus boots, DB online
┌─────────────────────────────────────────────────────────┐
│  TIER 2 - Tool Secrets (SecretVault DB, CAN migrate)    │
│  Read by agents AFTER full boot, via SecretVaultImpl    │
│                                                         │
│  GITHUB_TOKEN           ← coder agent                  │
│  ARCCLAW_TOOLS_SERPER_API_KEY ← researcher agent              │
│  ARCCLAW_TOOLS_BRAVE_API_KEY  ← researcher agent              │
└─────────────────────────────────────────────────────────┘
```

***

## Your Idea - Yes, Tier 2 Can Be Removed From `.env` After First Boot

`DevSecretSeeder` is **already designed for exactly this**. The flow is:

```
Boot 1 (fresh install):
  .env has GITHUB_TOKEN etc.
    → DevSecretSeeder reads them → stores in vault (encrypted)
    → idempotent: vault now has them permanently

Boot 2+ (ongoing):
  .env does NOT have GITHUB_TOKEN etc.
    → DevSecretSeeder: "already in vault, skipping" ✅
    → agents read directly from SecretVaultImpl ✅
```

***

## What Can NEVER Leave `.env` - The Vault Pepper Problem

`ARCCLAW_VAULT_PEPPER` has a **fundamental circular dependency** that makes it impossible to store in the vault:

```
To READ from vault → need VAULT_PEPPER to derive decryption key
To STORE VAULT_PEPPER → need vault to already be readable
                              ↑ impossible
```

This is the same reason a safe manufacturer can't lock the safe's combination inside itself. `ARCCLAW_VAULT_PEPPER` must always live outside the vault - in `.env`, an HSM, or a cloud secret manager.

***

## What `SecretVaultImpl` Actually Does (Full Picture)

```java
// Per-session key derivation (Argon2id - intentionally slow)
VaultKey sessionKey = keyCache.getOrDerive(sessionId, keyDerivation);
// ↑ Each user's secrets are encrypted with THEIR session-derived key
// (VAULT_PEPPER + sessionId) → unique per user, no cross-contamination

// Encryption: AES-GCM-SIV (nonce-misuse resistant)
AesGcmSivCipher.CipherResult result = cipher.encrypt(secret, sessionKey, keyName);
// ↑ keyName is AAD - prevents ciphertext being moved between key slots

// System keys bypass WorkspaceScope (safe at startup)
VaultKey systemKey = getSystemMasterKey(); // uses SYSTEM_SESSION_ID
// ↑ this is what DevSecretSeeder uses - no active session needed
```

***

## The Clean Prod `.env` After Migration

What your `.env` should look like in prod after first boot has seeded the vault:

```bash
# ── Framework (MUST stay) ──────────────────────
QUARKUS_PROFILE=prod
ARCCLAW_DATA_DIR=./data
ARCCLAW_VAULT_PEPPER=<base64>        # NEVER remove
ARCCLAW_AUDIT_HMAC_KEY=<base64>      # NEVER remove
ARCCLAW_LLM_API_KEY=<key>            # needed at boot for LangChain4J

# ── Tool secrets (remove after first boot) ─────
# GITHUB_TOKEN=...                   ← delete after boot 1
# ARCCLAW_TOOLS_SERPER_API_KEY=...         ← delete after boot 1
# ARCCLAW_TOOLS_BRAVE_API_KEY=...          ← delete after boot 1
```

`DevSecretSeeder` already handles the missing-key case gracefully with a `log.warnf` and a skip, so removing them from `.env` after seeding is safe with zero code changes.


----

What is the issue of removign tire 1 as well why cant it be top secrets Bootstrap like tire 2 ? llm keys are not top secret ? vault pepper and hmac keys also top secret right ?

Great challenge. Let me be precise about what is actually a **hard constraint** vs what is just a **current architectural limitation**.

***

## The ONLY True Hard Constraint

```
ARCCLAW_VAULT_PEPPER must exist outside the vault.
Mathematical fact. Cannot be solved in software alone.
```

This is not a design choice - it's a **cryptographic bootstrap paradox**:

```
To decrypt vault  → need VAULT_PEPPER
To store/read     → vault must already be decryptable
                         ↑
              VAULT_PEPPER cannot live here
```

Every secure system in existence has this same problem. The solutions are:

| Option | Example | ArcClaw today |
|---|---|---|
| Plain file | `.env` | ✅ current |
| OS Keychain | Windows Credential Manager, GNOME Keyring | ❌ not implemented |
| Cloud KMS | AWS Secrets Manager, GCP Secret Manager | ❌ not implemented |
| HSM/TPM | YubiKey, TPM chip | ❌ not implemented |
| Password at startup | Enter passphrase on boot | ❌ not implemented |

`.env` is just the **simplest implementation** of "something outside the vault". It could be replaced with OS Keychain - but the external anchor requirement never goes away.

***

## What Is NOT a Hard Constraint (Just Current Architecture)

```
ARCCLAW_LLM_API_KEY    ← solvable
ARCCLAW_AUDIT_HMAC_KEY ← already half-solved by DevSecretSeeder
```

**Why LLM key is in `.env` today:** LangChain4J reads config at Quarkus boot time via `@ConfigProperty`. At that moment, the vault is not online yet. So it reads from system properties → which come from `.env`.

**Could it be moved to vault?** Yes - but it requires:
```java
// Instead of @ConfigProperty (eager, boot-time):
@ConfigProperty(name = "arcclaw.llm.api-key")
String llmApiKey;  // ← read at boot, vault not ready

// You'd need lazy vault retrieval (post-boot):
@Inject SecretVault vault;

String getApiKey() {
    return vault.retrieveSystemKey("ARCCLAW_LLM_API_KEY")
                .map(VaultKey::toStringAndDestroy)
                .orElseThrow();
}
// ↑ only called when first LLM request fires, vault is fully online
```

This is an architectural refactor - not a hard impossibility.

***

## The Real Minimal `.env` After Full Migration

If you implemented OS Keychain for `VAULT_PEPPER` and lazy vault loading for LLM key:

```bash
# .env - theoretical minimum (non-secrets only)
QUARKUS_PROFILE=prod
ARCCLAW_DATA_DIR=./data

# Everything else lives in vault or OS keychain
# VAULT_PEPPER      → OS Keychain / TPM
# LLM_API_KEY       → SecretVault (loaded lazily)
# AUDIT_HMAC_KEY    → SecretVault (DevSecretSeeder already does this)
# GITHUB_TOKEN      → SecretVault (DevSecretSeeder already does this)
```

`DATA_DIR` and `QUARKUS_PROFILE` are **not secrets** - they're just config, no harm in them being in a file.

***

## Summary

| Secret | Can leave `.env`? | Blocker |
|---|---|---|
| `VAULT_PEPPER` | Only with OS Keychain/KMS | Hard constraint - external anchor always required |
| `LLM_API_KEY` | Yes | Needs lazy vault loading refactor |
| `AUDIT_HMAC_KEY` | Yes | DevSecretSeeder already handles it |
| `GITHUB_TOKEN` etc. | Yes ✅ | Already works today |
| `DATA_DIR`, `QUARKUS_PROFILE` | N/A | Not secrets, just config |


---

## Why Two Different HMAC Seeders

The audit HMAC key signs every audit log entry to prove it hasn't been tampered with. The **stability requirement** is completely different between dev and prod:

### Dev/Test - `seedAuditHmacKey()` (random ephemeral)
```java
byte[] hmacKeyBytes = new byte[32];
new SecureRandom().nextBytes(hmacKeyBytes);  // random every boot
secretVault.storeSystemKey(keyName, hmacKeyBytes);
```
**Why random?** In dev you wipe the DB constantly. If you used a fixed key from `.env`, every DB wipe would leave orphaned audit entries signed with the old key. A fresh random key per boot matches the fresh DB - consistency within a session is all that matters.

### Prod - `seedAuditHmacKeyFromEnv()` (stable from `.env`)
```java
String envValue = ConfigProvider.getConfig()
    .getOptionalValue("ARCCLAW_AUDIT_HMAC_KEY", String.class)...
secretVault.storeSystemKey(keyName, keyBytes);
```
**Why stable?** In prod, audit logs persist across reboots and must be **verifiable later**:

```
Boot 1 → HMAC key = X → signs entries 1-1000
Boot 2 → HMAC key = X → signs entries 1001-2000  ← same key, chain intact
Boot 3 → HMAC key = X → verifiable back to entry 1 ✅

vs random key:
Boot 1 → HMAC key = A → signs entries 1-1000
Boot 2 → HMAC key = B → entries 1-1000 now UNVERIFIABLE ❌
```

