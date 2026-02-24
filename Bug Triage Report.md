# Bug Triage Report — ArcClaw Falsification Audit

## SECTION 1: PRIORITIZED REMEDIATION BACKLOG

| Priority | Class::Method(Signature) | Bug Description | Runtime Consequence | Fix Complexity |
|----------|--------------------------|-----------------|---------------------|----------------|
| CRITICAL | `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` | Hardcoded `Map.of("GH_TOKEN", "GITHUB_TOKEN")` substituted when caller passes null/empty secrets map. | When `executeInContainer()` is called with a `null` or empty `requiredSecrets` map, `resolveSecrets()` silently substitutes a hardcoded `Map.of("GH_TOKEN", "GITHUB_TOKEN")`, causing the container to receive a `GH_TOKEN` environment variable that was never requested by the tool's `@RequiresSecret` annotation, and masking the upstream annotation-resolution failure in `ToolRegistry`. | TRIVIAL |
| CRITICAL | `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` | `System.getenv()` used as fallback when Database Vault returns empty, bypassing the secure vault contract. | When the Database Vault has no entry for a requested secret, `resolveSecrets()` silently falls back to `System.getenv(vaultKeyName)`, injecting a plaintext host environment variable into the container's env-file, bypassing the encrypted vault storage, key derivation, and audit trail entirely. | TRIVIAL |
| CRITICAL | `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` | `VaultKey` bytes converted to `String` via `new String(key.get(), UTF_8)` — an immutable plaintext copy persists on the JVM heap after `VaultKey.close()` zeros the source `byte[]`. | `resolved.put(envVar, new String(key.get(), StandardCharsets.UTF_8))` creates an interned/heap-resident `String` that cannot be zeroed. After `VaultKey.close()` wipes the source `byte[]`, the plaintext secret remains reachable in the `resolved` map and in the JVM string pool until GC, defeating the zero-on-close contract of `VaultKey`. | MEDIUM |
| HIGH | `ProcessIsolationExecutor::executeInNamespace(Path, List)` | `proc.waitFor()` return value discarded — method returns output regardless of whether the process timed out or failed. | `proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)` returns `false` on timeout, but the boolean is discarded. The method returns `out` to the caller containing partial or empty stdout, with no indication that the process is still running or exited with a non-zero code. The caller (`executeSandboxed`) treats this as a successful result. | TRIVIAL |
| HIGH | `ProcessIsolationExecutor::executeDirectly(Path, List)` | `proc.waitFor()` return value discarded — identical to `executeInNamespace`: process timeout and exit code silently ignored. | `proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)` returns `false` on timeout, but the boolean is discarded. The method returns `out` to the caller containing partial or empty stdout, with no indication that the process is still running or exited with a non-zero code. The caller (`executeSandboxed`) treats this as a successful result. | TRIVIAL |
| MEDIUM | `VaultKey::close()` | `destroyed` field is a plain `boolean` with no `volatile` or `synchronized` guard — concurrent `get()` and `close()` can race. | Thread A calls `close()`, sets `destroyed = true` and zeros `key`. Thread B concurrently calls `get()` → `checkDestroyed()`, reads stale `destroyed = false`, and returns a reference to the already-zeroed `byte[]`, yielding all-zero key material to the caller without raising `IllegalStateException`. | TRIVIAL |
| LOW | `PodmanHealthCheck::call()` | `process.destroyForcibly()` return value discarded on timeout path. | `process.destroyForcibly()` returns the `Process` object (for chaining `onExit()`). The return is discarded; if the OS fails to kill the process, the zombie is undetected. The health check returns DOWN regardless, so the consequence is a leaked OS process, not an incorrect health status. | TRIVIAL |
| LOW | `PodmanImageHealthCheck::call()` | `process.destroyForcibly()` return value discarded on timeout path — identical pattern to `PodmanHealthCheck`. | `process.destroyForcibly()` returns the `Process` object. The return is discarded; if the OS fails to kill the process, the zombie is undetected. The health check returns DOWN regardless, so the consequence is a leaked OS process, not an incorrect health status. | TRIVIAL |

---

## SECTION 2: SYSTEMIC PATTERNS

**PATTERN: DISCARDED_WAITFOR_RETURN**

Instances:
- `ProcessIsolationExecutor::executeInNamespace(Path, List)`
- `ProcessIsolationExecutor::executeDirectly(Path, List)`

Root Cause: Both fallback execution strategies were written as minimal copies of each other and neither checks the `boolean` return of `Process.waitFor(long, TimeUnit)` for timeout, nor inspects `exitValue()` for non-zero exit.

Structural Fix: Extract a shared `runProcessWithTimeout(ProcessBuilder, long)` method that captures `waitFor()`, enforces the timeout-destroyForcibly-throw pattern already implemented in `executeInContainer()`, and checks `exitValue() != 0`.

---

**PATTERN: CREDENTIAL_RESOLUTION_BYPASS**

Instances:
- `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` — hardcoded `Map.of("GH_TOKEN", "GITHUB_TOKEN")` fallback
- `ProcessIsolationExecutor::resolveSecrets(Map<String, String>)` — `System.getenv()` fallback

Root Cause: `resolveSecrets()` was designed with progressive degradation (Vault → env → hardcoded) instead of a strict fail-closed contract, allowing two distinct bypass paths around the encrypted vault.

Structural Fix: Remove both fallback paths and make `resolveSecrets()` fail-closed: if `requiredSecrets` is null/empty return an empty map; if the vault has no entry for a requested key, throw `SecurityException` immediately without checking `System.getenv()`.

---

**PATTERN: DISCARDED_DESTROYFORCIBLY_RETURN**

Instances:
- `ProcessIsolationExecutor::executeInContainer(String, Path, List, Map)`
- `PodmanHealthCheck::call()`
- `PodmanImageHealthCheck::call()`

Root Cause: `Process.destroyForcibly()` returns a `Process` handle that can be awaited via `onExit()`, but all three call sites discard it, assuming the OS kill is instantaneous and infallible.

Structural Fix: Introduce a utility `ProcessUtils.forceKillAndAwait(Process, Duration)` that calls `destroyForcibly().onExit().get(timeout)` and logs a warning if the process survives, eliminating the discarded-return pattern across all call sites.
