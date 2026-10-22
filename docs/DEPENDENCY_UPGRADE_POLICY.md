# Dependency Upgrade Policy

Reviewed 2026-10-22. This policy records the dependency boundary used for
routine maintenance after the Quarkus 3.40.1.1 LTS migration checkpoint.

## Platform policy

- Quarkus upgrades prioritize LTS releases. Use the official [Quarkus security page](https://quarkus.io/security/) to confirm active support lines and patch status before each migration (Quarkus 3.40.x LTS is the active baseline line as of 2026-10-22); confirm the exact stable platform version and published member BOMs before changing the platform pin.
- On 2026-09-12, **Path A (Quarkus 3.39.3 staging checkpoint)** was ratified as an authorized regular-stream staging step toward Quarkus 3.40 LTS. Quarkus 3.39 serves as the upstream foundation for 3.40 LTS; this staging step validates integration changes (Quarkiverse MCP Server 2.0.0, LangChain4j 1.19.0, Camel 4.22.0, Bouncy Castle 1.85.2) before 3.40 LTS freezes.
- `3.39.0.CR1` and other release candidates remain excluded from production dependency updates.
- Quarkus, Camel, LangChain4j, and MCP member BOMs move only with the selected Quarkus platform.

### Current LTS baseline

Quarkus `3.40.1.1` is the current platform baseline after the 2026-10-22 migration
checkpoint. See the official [Quarkus 3.40 LTS announcement](https://quarkus.io/blog/quarkus-3-40-released/)
and [3.40.1.1 LTS release](https://quarkus.io/blog/quarkus-3-40-1-1-released/).
The Quarkus, LangChain4j, Camel, and MCP member BOMs use the same `3.40.1.1`
platform line; their managed runtime families resolve to Camel Quarkus `3.40.0`
(Camel `4.22.1`), LangChain4j core `1.19.3` / extension `1.13.3`, and MCP Server `2.0.1`.

The macOS arm64 JVM verification and native build are the current local platform
coverage. Linux and Windows native build/runtime validation remains pending; keep
that status visible and do not imply cross-platform native validation is complete.
The ordered migration evidence is in the local tracker at
`.scratch/quarkus-upgrade/issues/10-quarkus-340-lts.md`.

## Routine independent updates

Only explicitly versioned, independently maintained dependencies and build
plugins may be upgraded without a platform migration. The current low-risk
batch is:

| Component | Previous | New | Scope |
| --- | ---: | ---: | --- |
| WireMock | `3.9.2` | `3.13.2` | Test-only HTTP fixtures |
| JBoss LogManager | `3.2.1.Final` | `3.2.2.Final` | JVM/test logging |
| JLine | `3.30.11` | `3.30.16` | CLI support; remain on 3.x |
| JaCoCo | `0.8.14` | `0.8.15` | Coverage tooling |
| Spotless Maven plugin | `3.5.1` | `3.10.0` | Formatting gate |
| Exec Maven plugin | `3.5.0` | `3.6.3` | Verification task |
| Maven Enforcer plugin | `3.5.0` | `3.6.3` | Build policy |
| Maven Dependency plugin | `3.8.1` | `3.11.0` | SkillsJar resource unpacking |
| Maven Antrun plugin (`app`) | `3.1.0` | `3.2.0` | Build resource tasks |

These updates still require the normal JVM build, tests, PMD, and formatting
checks. They do not imply that a native-sensitive dependency is compatible.

## Sensitive independent dependencies held back

These dependencies are independently maintained and explicitly pinned, but
their upgrades can change application behavior. They require focused evidence
before changing:

| Component | Candidate | Required evidence |
| --- | ---: | --- |
| Apache Lucene | `10.5.1` | Native image initialization, reflection, FS search, and full FS validation |
| DJL Hugging Face tokenizers | `0.36.0` | MPNet tokenization, truncation/max-sequence, and embedding regression tests |
| ONNX Runtime | `1.29.0` | Native library loading, model inference, provenance, and ONNX release gates |
| Bouncy Castle | `1.86` | Platform-managed by `quarkus-bom:3.40.1.1`. Root `pom.xml` `<bouncycastle.version>` aligned to `1.86` on 2026-10-22 (Issue 10 §2.3 / §4.3). Silent-downgrade risk is **resolved and closed**; version alignment plus local macOS arm64 native builds/smoke verification passed. Explicit provider-initialization coverage and the multi-platform native matrix remain deferred to CI before production promotion. |
| Bucket4j | `8.19.0` | Rate-limit, authentication, and REST behavior tests |
| OpenAPI Generator | `7.25.0` | Generated TypeScript DTO diff, UI type-check, UI build, and UI tests |

Do not update these merely because Maven reports a newer version. Preserve the
current native and functional baseline until the corresponding evidence is
available.

## Excluded transitive and BOM-managed updates

The following are intentionally not upgraded independently:

- Quarkus platform artifacts and the Camel, LangChain4j, and MCP member BOMs.
- Quarkus extensions whose versions are supplied by the platform BOM.
- Jackson, OpenTelemetry, Mockito, LangChain4j core, and Quarkiverse LangChain4j extensions when resolved through the selected BOM.
- Byte Buddy agent, whose inherited test dependency version is platform-managed; the local Surefire property must stay aligned with the resolved JAR.
- Other transitive libraries whose versions are controlled by those BOMs.

These versions must move as part of a tested Quarkus LTS platform change. A
direct override would split the tested dependency graph and can invalidate
native-image, serialization, provider, or delegation behavior.

## Verification

For an independent update, run:

```text
./mvnw clean install -DskipTests
./mvnw test
./mvnw -pl app -am pmd:check -DskipTests
./mvnw spotless:check -q
python3 ./scripts/verify_docs.py
```

For a held native-sensitive update, additionally run the relevant native
distribution, local-runtime, native-matrix, and ONNX provenance gates before
promoting the version.
