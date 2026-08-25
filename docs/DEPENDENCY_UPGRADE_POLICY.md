# Dependency Upgrade Policy

Reviewed 2026-08-25. This policy records the dependency boundary used for
routine maintenance after the Quarkus 3.38.3 migration.

## Platform policy

- Quarkus upgrades are LTS-only. The official [Quarkus security page](https://quarkus.io/security/) currently lists the 3.33 and 3.27 lines as LTS; Maven Central currently publishes `3.33.3.1` as the latest 3.33 patch.
- Quarkus `3.38.3` is a regular maintenance release, not an LTS release. Moving the project back to the 3.33 LTS line is a separate compatibility task and must not be combined with routine library refreshes.
- `3.39.0.CR1` and other release candidates are excluded from production dependency updates.
- Quarkus, Camel, LangChain4j, and MCP member BOMs move only with the selected Quarkus LTS platform.

### Next LTS backlog target

Quarkus `3.40 LTS` is tracked as the next target for **September 2026
(targeted)**. It is not an available upgrade until the final platform and
member BOMs are published and the official support/security pages identify the
line as LTS. See [Quarkus 3.40 LTS backlog issue](../.scratch/quarkus-upgrade/issues/10-quarkus-340-lts.md).

The 3.40 work must begin with a fresh updater dry-run and migration-guide
review, then prove JVM, persistence, native, REST, delegation, ML, and release
evidence gates before changing the platform pin.

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
| Bouncy Castle | `1.85.2` | Platform alignment, security-provider tests, and JVM/native startup |
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
