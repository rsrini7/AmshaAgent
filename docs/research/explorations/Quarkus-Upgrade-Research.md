# Quarkus 3.38 Upgrade Research

- **Status**: Historical Research (completed baseline & checkpoint comparison)
- **Research Date**: 2026-08-23 (Asia/Kolkata)
- **Role**: Architecture decision input for [ADR-Quarkus-Upgrade-Strategy](../../adr/architecture/ADR-Quarkus-Upgrade-Strategy.md). The repository now targets Quarkus `3.38.3`; the 3.27.2 and 3.33.3.1 references below are baseline and checkpoint comparisons.

## Executive finding

Quarkus **3.38.3** is the latest stable community release as of the research
date. It was released on 2026-08-19 as a maintenance release for the 3.38 train,
and Quarkus says it should be safe for applications already on 3.38. Quarkus
also says its updater can migrate applications from any Quarkus version,
including 2.x, to 3.38. That does not mean a 3.27-to-3.38 migration is safe to
apply without review: the official update guide labels the facility
experimental and explicitly says it automates only a subset of migration work.
The guide requires reviewing every intervening migration guide and validating
the application after the rewrite.

Sources:

- [Quarkus 3.38.3 release, 2026-08-19](https://quarkus.io/blog/quarkus-3-38-3-released/)
- [Official Quarkus update guide](https://quarkus.io/guides/update-quarkus)
- [Quarkus migration-guide index](https://github.com/quarkusio/quarkus/wiki/Migration-Guides)

For this repository, the best risk-adjusted plan is:

1. establish a green 3.27.2 baseline;
2. take the same-stream security/native fix checkpoint at **3.27.5.1**;
3. migrate to the newest LTS checkpoint, **3.33.3.1**;
4. decide whether production should remain on 3.33 LTS or continue to the
   requested latest stable, **3.38.3**;
5. if continuing, migrate 3.33.3.1 to 3.38.3 as a separate, independently
   reversible change.

The final target can be 3.38.3, but 3.33.3.1 is a valuable diagnostic and
rollback boundary. It is also the lower-change production choice when no
3.34-3.38 feature is required.

## Release and support choices

| Stream | State on 2026-08-23 | First-release date | Latest patch observed | Practical interpretation |
| --- | --- | --- | --- | --- |
| 3.27 | Current repository stream; LTS | 2025-09-24 | 3.27.5.1, released 2026-08-05 | Still nominally inside the community's 12-month LTS window, but the repository is behind its security/native maintenance patch. |
| 3.33 | Newest released LTS | 2026-03-25 | 3.33.3.1, released 2026-08-05 | Recommended stability checkpoint and defensible production target when latest features are not required. |
| 3.38 | Latest regular stream | 2026-07-29 | 3.38.3, released 2026-08-19 | Requested latest target; it has a shorter stability history and monthly-stream change cadence. |

Quarkus states that community LTS streams receive critical fixes, security
patches, and performance fixes for **12 months**, and that a new LTS is planned
roughly every six months. Regular streams continue the monthly-ish feature
cadence. On that published policy, 3.27 is nominally supported until around
September 2026 and 3.33 until around March 2027; those dates are policy-derived,
not separate contractual support commitments.

Primary sources:

- [Quarkus LTS policy](https://quarkus.io/blog/lts-releases/)
- [Migration-guide index showing 3.27 and 3.33 as LTS and release dates](https://github.com/quarkusio/quarkus/wiki/Migration-Guides)
- [Quarkus 3.27.5.1 LTS emergency release, 2026-08-05](https://quarkus.io/blog/quarkus-3-27-5-1-released/)
- [Quarkus 3.33.3.1 LTS emergency release, 2026-08-05](https://quarkus.io/blog/quarkus-3-33-3-1-released/)

Both 3.27.5.1 and 3.33.3.1 fix CVE-2026-16308 (Quarkus REST unbounded
multipart MIME part-header accumulation) and a native-image regression from a
Netty upgrade. The 3.27 release also upgrades the PostgreSQL driver to 42.7.13.
This makes the 3.27.2-to-3.27.5.1 checkpoint useful on its own, even before a
cross-stream migration.

## Comprehensive repository baseline and library inventory

The root build compiles with Java 25 and coordinates 28 Maven modules (29
reactor projects counting the root aggregator). The table
below summarizes the baseline version controls against the proposed target platform:

| Library / Family | Current control in root POM | Current version | Target in 3.38.3 platform | Disposition |
| --- | --- | --- | --- | --- |
| Quarkus core platform | `io.quarkus.platform:quarkus-bom` | `3.27.2` | `3.38.3` | Platform BOM |
| Quarkus LangChain4j | `io.quarkus.platform:quarkus-langchain4j-bom` | `3.27.2` | `3.38.3` | Member BOM |
| Camel Quarkus | `org.apache.camel.quarkus:camel-quarkus-bom` | `3.27.3` | `3.38.0` | Switch to `io.quarkus.platform:quarkus-camel-bom` |
| Quarkiverse MCP Server | Direct dependency versions | `1.10.3` | `1.13.1` | Switch to `io.quarkus.platform:quarkus-mcp-server-bom` |
| Quarkiverse JDBI | Direct dependency version | `1.9.0` | `1.10.1` | Pinned Quarkiverse property; see JDBI notes |
| Quarkiverse SQLite JDBC | Direct dependency version | `3.0.11` | `3.0.11` (latest published) | Keep pinned; prove against 3.38 native |
| LangChain4j libraries | Managed by member BOM | `1.5.0` (`beta11`) | `1.17.2` (`beta27`) | Member BOM; compile gate required |
| Stale `<langchain4j.version>` | Root property | `1.0.0-beta1` | unused | Remove; no module references it |
| PostgreSQL JDBC Driver | Managed by `quarkus-bom` | `42.7.7` | `42.7.13` | Managed by platform BOM |
| Flyway Migration | Managed by `quarkus-bom` | `11.11.2` | `12.0.0` | Major bump via platform; see Flyway notes |
| Byte Buddy Agent | Root pin + inherited test dep | `1.17.6` | `1.18.8` (BOM) | Pin forces old Byte Buddy; align or drop pin |
| jboss-logmanager | Root pin + inherited test dep | `3.0.6.Final` | `3.2.2.Final` (BOM); `3.1.2.Final` today | Pin already below current BOM; must fix |
| Bouncy Castle | Root pin | `1.78.1` | `1.84` (BOM); `1.82` today | Pin already below current BOM; align or justify |
| commons-lang3 | Root pin | `3.18.0` | `3.20.0` (BOM); equal today | Drop pin at cross-stream checkpoints |
| commons-text | Root pin | `1.15.0` | `1.15.0` (BOM target) | Already aligned with 3.38.3 value |
| ArchUnit | Root pin | `archunit-junit5` `1.4.1` | `1.5.0`; switch to `archunit-junit6` | Required for JUnit 6 engine alignment |
| WireMock Standalone | Direct dependency version | `3.9.2` | `3.9.2` (test scope) | Verify under JUnit 6/Surefire 3.5 |
| Zonky embedded-postgres | Direct dependency version | `2.2.2` | `2.2.2` (test scope) | Independent of Quarkus; no change expected |
| OpenInference LangChain4j | Direct dependency version | `0.1.8` | declared, not referenced in Java code | Candidate for pruning; OTel bump may affect it |
| DJL HuggingFace Tokenizers | Direct dependency version | `0.33.0` | `0.33.0` (offline enforced) | Optional later bump; verify JNI behavior first |
| ONNX Runtime | Direct dependency version | `1.27.0` | `1.27.0` | Direct pin; single-threaded native mode |
| Apache Lucene | Direct dependency version | `10.4.0` | `10.4.0` pinned | Must stay aligned with `Lucene104*` reflection |
| Bucket4j | Direct dependency version | `8.16.1` | `8.16.1` | Independent of Quarkus |
| CronUtils | Direct dependency version | `9.2.1` | `9.2.1` | Independent of Quarkus |
| OWASP HTML Sanitizer | Direct dependency version | `20260313.1` | `20260313.1` | Independent of Quarkus |
| JLine3 / Jansi | Direct dependency versions | `3.30.11` / `2.4.3` | unchanged | Independent CLI terminal support |
| Error Prone Core | Compiler plugin processor | `2.49.0` | `2.49.0` | JDK 25 compatible; keep exports in `.mvn/jvm.config` |
| Maven PMD Plugin | Direct plugin version | `3.28.0` | `3.28.0` | Build plugin with PMD 7 engine |
| Maven Wrapper | Wrapper properties | `3.9.12` | `3.9.16` | Toolchain prerequisite |

### Verified platform-managed version moves (quarkus-bom diff)

These values were extracted from the published
[`io.quarkus:quarkus-bom` 3.27.2](https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.27.2/quarkus-bom-3.27.2.pom)
and [3.38.3](https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.38.3/quarkus-bom-3.38.3.pom)
artifacts rather than inferred from guides. Any root-POM pin that differs from a
target value silently overrides the platform and must be reconciled or justified
at every checkpoint.

| Family | In 3.27.2 | In 3.38.3 | Amsha exposure |
| --- | --- | --- | --- |
| Mutiny (`io.smallrye.reactive:mutiny`) | `2.9.5` | `3.3.0` | Major-version jump inside the platform; all `Uni`/`Multi` call sites |
| Vert.x core | `4.5.23` | `4.5.32` | Patch-level; `quarkus-vertx`, WebSockets Next |
| Mutiny Vert.x bindings | `3.21.3` | `3.23.0` | Generated API surface only |
| Netty | `4.1.130.Final` | `4.1.137.Final` | Patch-level CVE/bugfix train |
| `io.quarkus.http` (Undertow-like core) | `5.3.5` | `5.5.0` | HTTP layer internals |
| Jackson (databind/core/yaml) | `2.19.2` | `2.22.0` | REST/Jackson, config-yaml, LangChain4j guardrails |
| Hibernate ORM | `7.1.14.Final` | `7.4.5.Final` | Present via `llm-providers` and profile-specific deps |
| Hibernate Validator | `9.0.1.Final` | `9.1.3.Final` | Bean validation on REST resources |
| Agroal | `2.8` | `3.2.1` | Connection pooling for both JDBC backends |
| Narayana JTA | `7.2.2.Final` | `7.3.4.Final` | Transaction interceptor ordering changes in 3.31 |
| Flyway core + database modules | `11.11.2` | `12.0.0` | Major bump; see persistence notes |
| PostgreSQL JDBC driver | `42.7.7` | `42.7.13` | `%postgresql` backend |
| Micrometer | `1.14.7` | `1.17.0` | Prometheus registry; strict tag-set enforcement from 3.34 |
| OpenTelemetry SDK | `1.44.1` | `1.62.0` | Custom exporter config, span processors |
| OTel instrumentation | `2.10.0` | `2.28.1` | Embedded by `quarkus-opentelemetry` |
| OTel semantic conventions | `1.28.0-alpha` / `1.29.0-alpha` | `1.41.1` / `1.40.0-alpha` | Hardcoded `gen_ai.*`/`openinference.*` attribute strings |
| REST Assured | `5.5.6` | `6.0.1` | All REST integration tests |
| JUnit Jupiter / Platform | `5.13.4` / `1.13.4` | `6.1.3` / `6.1.3` | Unified versioning; artifact relocations in 3.31 |
| Testcontainers | `1.21.4` | `2.0.5` | Artifact and package renames; PG tests only |
| Mockito | `5.18.0` | `5.21.0` | Unit tests across modules |
| SmallRye OpenAPI | `4.0.12` | `4.3.5` | Generated OpenAPI drives TS DTO generation |
| SmallRye Health | `4.2.0` | `4.3.0` | Health endpoints |
| SmallRye Fault Tolerance | `6.9.3` | `6.11.2` | `@Retry`/circuit breakers in `core`/`mcp` |
| SmallRye Context Propagation | `2.2.1` | `2.3.0` | Workspace context propagation |
| Jandex runtime | `3.4.0` | `3.6.0` | Extension indexing; seven modules pin plugin `3.5.3` vs parent `3.6.0`; align at Phase 2 |
| SnakeYAML | `2.4` | `2.6` | `quarkus-config-yaml` profiles |
| Caffeine | `3.2.3` | `3.2.4` | `quarkus-caffeine` caches |
| picocli | `4.7.7` | `4.7.7` (unchanged) | CLI dual-mode |
| Graal SDK | `23.1.2` | `23.1.2` (unchanged) | Native tooling uses Mandrel/GraalVM 25 images |

LangChain4j coordinates come from the member BOMs:
[`quarkus-langchain4j-bom` 3.27.2](https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-langchain4j-bom/3.27.2/quarkus-langchain4j-bom-3.27.2.pom)
manages LangChain4j `1.5.0` (with `1.5.0-beta11` beta modules) while
[3.38.3](https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-langchain4j-bom/3.38.3/quarkus-langchain4j-bom-3.38.3.pom)
manages `1.17.2` (with `1.17.2-beta27`). The Camel and MCP member BOMs at 3.38.3
manage Camel Quarkus `3.38.0` and quarkus-mcp-server `1.13.1` respectively.
Quarkiverse parent POMs show quarkus-jdbi `1.9.0` was built against Quarkus
`3.30.2` with Jdbi `3.50.0`, and `1.10.1` against Quarkus `3.38.0` with Jdbi
`3.54.0`; `quarkus-jdbc-sqlite` `3.0.11` is still the latest published release
(October 2024) and its parent declares Quarkus `3.15.1` and xerial
`sqlite-jdbc` `3.46.1.3`.

See [root `pom.xml`](../../../pom.xml) for existing property definitions and BOM
imports. The build uses Temurin 25 and Maven 3.9 via [mise.toml](../../../mise.toml),
while the [Maven wrapper](../../../.mvn/wrapper/maven-wrapper.properties) is pinned
to Maven **3.9.12**. The 3.38.3 update guide requires JDK 17+ and Maven
**3.9.16**, so the wrapper must be updated and validated before using the
documented updater command. Quarkus 3.38.3 documents Java 17, 21, and 25 as its
supported Java versions; 3.31 is the stream that introduced Java 25 support out
of the box.

Sources:

- [Quarkus 3.38.3 update prerequisites](https://quarkus.io/guides/update-quarkus#prerequisites)
- [Quarkus 3.38.3 documented JDK versions in source](https://github.com/quarkusio/quarkus/blob/3.38.3/docs/src/main/asciidoc/_attributes.adoc)
- [Quarkus 3.31 migration guide: Java 25](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31#maven-and-java-25)

This reactor contains a custom Quarkus extension: `amsha-core` is paired with
the [`deployment` module](../../../deployment). Its build steps consume
augmentation APIs such as `ContextRegistrationPhaseBuildItem`,
`CustomScopeBuildItem`, `UnremovableBeanBuildItem`, `ReflectiveClassBuildItem`,
and `NativeImageResourceBuildItem`. The runtime also references the Arc
implementation marker `io.quarkus.arc.Subclass`. Recipe success and ordinary
compilation cannot establish semantic compatibility for these deployment APIs.

Native image is a release target rather than an optional check. The repository
has centralized reflection configurations across modules, custom generated
native resources, a JDK 25 Mandrel builder image, local GraalVM 25.0.2 tooling,
and separately baked SQLite, PostgreSQL, and filesystem native variants. JVM
tests alone cannot close this migration.

## In-depth upstream ecosystem and library analysis

### 1. Quarkus core platform, Arc/CDI, concurrency, and augmentation

- **Arc `Subclass` internal marker**:
  `io.quarkus.arc.Subclass` is an internal Arc interface implemented by intercepted
  proxy subclasses. `ToolInvocationGuard.java` uses `interceptor instanceof Subclass`
  to locate unproxied interceptor annotations. While `Subclass` remains available in
  Quarkus 3.38, standardizing proxy unwrapping via `ClassUtils.getCleanName()` or
  defensive type hierarchy inspection protects against future internal Arc refactorings.
- **Default interface method interception (Quarkus 3.37)**:
  Starting in 3.37, Arc applies interceptor bindings declared on default interface
  methods. An audit of interface default methods across `channel-api`, `core`,
  `persistence-api`, and `classifier-api` confirmed none declare interceptor bindings,
  ensuring zero behavioral regression.
- **Augmentation build items immutability (Quarkus 3.32)**:
  Lists of `MultiBuildItem` consumed in `@BuildStep` methods became immutable in 3.32.
  Auditing `AgentFunctionProcessor`, `RestResourceBuildStep`, `SkillsJarBuildStep`, and
  `WorkspaceScopeProcessor` proved that no Amsha build step mutates consumed lists in-place.
- **REST concurrency and `@Transactional` (Quarkus 3.35)**:
  Quarkus REST in 3.35+ no longer treats `@Transactional` as an implicit blocking
  marker. Because Amsha's `RestResourceBuildStep` already enforces `@RunOnVirtualThread`
  at class level on all JAX-RS resources and confines database transactions to DAO/store
  boundaries, endpoints run safely on virtual threads without event-loop blocking.
- **Qute `TemplateException` wrapping (Quarkus 3.29)**:
  Qute wraps evaluation errors in `io.quarkus.qute.TemplateException`. Code in
  `PromptTemplateEngine` and prompt tests must inspect `e.getCause()` when asserting
  underlying root causes.
- **Mandrel/GraalVM 25 reachability metadata format**:
  Mandrel 25 adopts standard `reachability-metadata.json`. Configuration property
  `quarkus.native.resources.excludes` is deprecated in 3.29. Amsha does not use resource
  exclusions and registers all native resources explicitly via `NativeImageResourceBuildItem`.

### 2. AI, LangChain4j, local ML, tokenization, and vector search

- **LangChain4j `1.5.0` to `1.17.2` via the member BOM**:
  The platform member BOM moves every `dev.langchain4j` coordinate twelve minor
  releases forward. The modern surface Amsha already uses remains the main API:
  `ChatModel.chat(ChatRequest)` returning `ChatResponse`, per
  [the ChatModel javadoc](https://docs.langchain4j.dev/apidocs/dev/langchain4j/model/chat/ChatModel.html);
  `chat(ChatRequest, ChatRequestOptions)` was added in `1.13.0`. Because
  LangChain4j publishes breaking-change notes per release rather than a single
  migration guide, Phase 3 requires compiling `llm-providers`, `rag`,
  `persistence-*`, and `channel-telegram` against `1.17.2` and reviewing the
  [release notes index](https://docs.langchain4j.dev/latest-release-notes/) for
  deprecations touching tool execution, streaming, and content types. This bump
  cannot be avoided: the 3.27-era `1.5.0` line is not managed by the 3.38
  platform.
- **Root POM cleanup**:
  The unmanaged `<langchain4j.version>1.0.0-beta1</langchain4j.version>` property in root
  `pom.xml` should be removed since `quarkus-langchain4j-bom` manages all coordinates;
  no module references the property today.
- **OpenInference instrumentation**:
  `openinference-instrumentation-langchain4j` is declared in POMs but not directly referenced
  in Java code. Amsha natively exports OpenInference and GenAI semantic conventions via
  `AgentOTelConstants` (`gen_ai.system`, `openinference.span.kind`, `llm.*`). Because these
  are literal strings compiled into Amsha code, OTel semconv version moves inside the
  platform do not rename them; dashboards must instead be checked against exporter output
  during the telemetry gate.
- **DJL Tokenizers (0.33.0)**:
  `ai.djl.huggingface:tokenizers` 0.33.0 stays as-is for this migration; upstream releases
  beyond it are optional follow-up work that must re-validate native JNI behavior and the
  truncation bound before adoption. Offline gating (`ai.djl.offline=true`)
  remains active.
- **ONNX Runtime (1.27.0)**:
  `com.microsoft.onnxruntime:onnxruntime` 1.27.0 supports ONNX Opset 7 through 22. JDK 25
  restricted native access is satisfied by `--enable-native-access=ALL-UNNAMED`.
  Single-threaded intra/inter-op execution prevents native thread starvation.
- **Apache Lucene (10.4.0) codec reflection invariant**:
  The `persistence-fs` native `reflect-config.json` explicitly registers versioned codecs
  (`Lucene104Codec`, `Lucene104HnswScalarQuantizedVectorsFormat`). Lucene must remain pinned
  at `10.4.0` to preserve native image vector search reachability.

### 3. Camel Quarkus, MCP Server, and channel transports

- **Camel Quarkus 3.38.0 via the member BOM**:
  Switching from external `camel-quarkus-bom` to `io.quarkus.platform:quarkus-camel-bom`
  eliminates version skew against Quarkus core. Route definitions in
  `TelegramChannelRoute` (`telegram:bots`, `TELEGRAM_CHAT_ID` header) are expected to
  carry over, but source compatibility across the Camel core range spanned by CQ
  `3.27.3` to `3.38.0` must be demonstrated by compiling `channel-telegram` and running
  its route tests, not assumed.
- **Camel test framework relocation is mandatory, not optional**:
  Per the [CQ 3.31 migration guide](https://camel.apache.org/camel-quarkus/next/migration-guide/3.31.0.html),
  `camel-quarkus-junit5` was renamed to `camel-quarkus-junit`, test imports moved from
  `org.apache.camel.test.junit5` to `org.apache.camel.test.junit6`, and the old artifact
  is **removed in Camel Quarkus 3.34**. Because the 3.38.3 member BOM brings CQ
  `3.38.0`, keeping `camel-quarkus-junit5` (declared by both `app` and
  `channel-telegram`) would resolve dead coordinates once that member BOM
  lands in Phase 3. Renaming proactively at Phase 2 pairs the switch with the
  JUnit 6 engine move while the old artifact still resolves on the CQ line of
  this checkpoint; completion becomes mandatory at Phase 3.
- **Quarkiverse MCP Server `1.13.1`**:
  Managed via `io.quarkus.platform:quarkus-mcp-server-bom`; the published
  [3.38.3 member BOM](https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-mcp-server-bom/3.38.3/quarkus-mcp-server-bom-3.38.3.pom)
  pins `io.quarkiverse.mcp` coordinates at exactly `1.13.1`, and the Quarkus
  extension registry lists `1.13.1` as the latest release of this line. The
  [1.13.0 release notes](https://github.com/quarkiverse/quarkus-mcp-server/releases/tag/1.13.0)
  document the behavior changes carried into the pinned patch release:
  Streamable HTTP **lazy-initializes SSE by default** (behavior change, not a new
  config flag), honors client metadata on auto-init, adds elicitation URL mode per MCP
  spec revision 2025-11-25, adds `OptionalInt`/`OptionalLong`/`OptionalDouble` tool
  parameters, tracing spans carrying `mcp.server.name`, and a new
  `quarkus-mcp-server-oidc` extension. Upstream has published `2.0.0.CR1/CR2`
  targeting newer Quarkus; the 3.38.3 platform still pins the `1.13.x` line, so no CR
  adoption happens here. MCP HTTP and stdio transports must be smoke-tested because
  `1.10.3` to `1.13.1` crosses several protocol-revision features.
- **WebSockets Next**:
  `quarkus-websockets-next` in `channel-web` rides the platform; no guide items were
  listed for it between 3.28 and 3.38, but its Vert.x underpinning moves
  (`4.5.23` to `4.5.32`), so the WebSocket channel tests remain mandatory.

### 4. Persistence, connection pooling, migrations, and transactions

- **Agroal pool default max-size increase (20 -> 50 in Quarkus 3.31)**:
  Quarkus 3.31 increased default pool max-size from 20 to 50. Amsha explicitly specifies
  `max-size: 3` (SQLite) and `max-size: 10` (`%postgresql`) in `application.yaml`, preventing
  connection pool explosion against embedded or cloud PostgreSQL databases.
- **Agroal itself moves `2.8` to `3.2.1`**:
  The pool implementation crosses a major line inside the platform. Pool sizing,
  `max-size` semantics, and datasource health must be re-proven on both JDBC backends;
  the explicit YAML limits above remain the primary control.
- **Quarkiverse JDBI `1.10.1` (Jdbi `3.54.0`, built against Quarkus `3.38.0`)**:
  The currently pinned `1.9.0` was built against Quarkus `3.30.2` with Jdbi `3.50.0`,
  so it already floats ahead of this repository's 3.27.2 platform today; `1.10.1` is the
  first release whose build baseline matches the 3.38 stream.
  `ConstructorMapper` and `@BindMethods` on Java 25 records are the patterns to re-verify
  with focused store tests after the bump.
- **Quarkiverse SQLite JDBC `3.0.11` is a verification gate, not a version choice**:
  `3.0.11` remains the latest published release of the extension (October 2024), built
  against Quarkus `3.15.1` and bundling xerial `sqlite-jdbc` `3.46.1.3`. There is no
  newer release to move to, which means compatibility with the 3.38 augmentation and
  native image pipeline can only be established by building and smoking the SQLite
  native variant. The bundled driver, SQLite engine, FTS5, and `vec0` extension loading
  stay byte-identical across this migration because no newer driver arrives; an
  experimental `quarkus-jdbc-sqlite4j` alternative exists upstream but is not part of
  this decision.
- **PostgreSQL JDBC driver `42.7.7` to `42.7.13`**:
  Patch-level maintenance train delivered by the platform BOM (the 3.27.5.1 patch
  already ships `42.7.13`). TLS 1.3 becomes the Quarkus default from 3.31, negotiated
  automatically with modern PostgreSQL servers.
- **Flyway `11.11.2` to `12.0.0` is a major-version jump managed by the platform**:
  Flyway 12's published breaking changes center on Java API surface (`CommandExtension`
  signature changes, `CompositeResult` becoming a record) that quarkus-flyway shields
  from applications, plus licensing-module recognition for Redgate editions that does
  not apply to community usage here. The practical gates are behavioral: V1-V11 schema
  history compatibility for SQLite and PostgreSQL, checksum stability of existing
  applied migrations, and startup ordering under the new engine. No new SQL feature is
  adopted in this migration.

### 5. Security, observability, utilities, and build tooling

- **Micrometer `1.14.7` to `1.17.0` and strict tag consistency (Quarkus 3.34+)**:
  Starting in Quarkus 3.34+, registering meters with identical names but differing tag key
  sets throws instead of silently dropping the later registration. Custom meters exist
  across shadow classifiers, tool execution, ONNX inference, and agent loops; every
  registration site must be audited and metric startup/runtime tests executed rather
  than assumed compliant.
- **OpenTelemetry SDK `1.44.1` to `1.62.0` and instrumentation `2.10.0` to `2.28.1`**:
  `LateBoundSpanProcessor` was removed in Quarkus 3.38. `PhoenixExporterConfig` configures
  standard `BatchSpanProcessor` directly on `SdkTracerProvider`, avoiding removed hooks.
  Semantic-convention artifacts move several alpha lines; because Amsha owns its GenAI/
  OpenInference attribute strings in `AgentOTelConstants`, emitted attribute names do not
  change implicitly, but exporter output must be diffed during the telemetry gate to catch
  platform-side attribute renames.
- **Bouncy Castle pin is below even the current platform**:
  `quarkus-bom` 3.27.2 already manages `bcprov-jdk18on`/`bctls-jdk18on` at `1.82`; the
  root pin at `1.78.1` forces every consumer below platform expectations today, and the
  3.38.3 target manages `1.84`. Native reflection partitions in `amsha-security`
  (build-time crypto classes vs run-time PRNG classes such as
  `org.bouncycastle.jcajce.provider.drbg`) must be re-validated against whichever jar
  wins resolution; aligning the pin to the target BOM value is the recommended default.
- **Byte Buddy pin versus platform-managed `1.18.8`**:
  Byte Buddy has officially supported Java 25 class files since `1.17.0`, per the
  project's compatibility table. Aligning the test dependency and javaagent path to
  `1.18.8` keeps Mockito `5.21` and Quarkus proxies coherent and makes
  `-Dnet.bytebuddy.experimental=true` unnecessary for JDK 25 class files; drop the flag
  only after the full JVM suite passes without it. Note that Byte Buddy `1.18.9`
  disables `sun.misc.Unsafe` usage by default on Java 25+; the platform pins `1.18.8`,
  which predates that flip.
- **Bucket4j rate limiting (8.16.1)**:
  Uses lock-free CAS state management (`AtomicReference`/`AtomicLong`), making token
  consumption fully non-blocking and safe under `@RunOnVirtualThread`.
- **Surefire / Failsafe `@{argLine}` late evaluation**:
  Quarkus 3.31 requires late evaluation syntax `@{argLine}` in Maven Surefire and Failsafe
  plugins to ensure JaCoCo dynamic agent injection and Java 25 `-XX:+EnableDynamicAgentLoading`
  flags compose without being overwritten.
- **Error Prone 2.49.0 & PMD 7**:
  Error Prone runs with 10 internal `javac` exports in `.mvn/jvm.config` and
  `--should-stop=ifError=FLOW`. PMD 3.28.0 runs PMD 7 engine with Java 25 language support.

### 6. Reactive core, serialization, and logging internals

- **Mutiny `2.9.5` to `3.3.0` is the largest hidden runtime change**:
  The platform replaces Mutiny 2.x wholesale. The
  [release history](https://github.com/smallrye/smallrye-mutiny/releases)
  shows the 3.x line cleared its RevAPI breaking-change justifications at `3.0.0` and
  then added features (demand pausing, memoization of failures, typed
  `onFailure(RuntimeException.class)`). Code that only consumes `Uni`/`Multi` through
  normal operators is expected to migrate transparently, as happened for Mutiny 1-to-2,
  but any direct use of infrastructure APIs, custom operators, or dropped deprecated
  methods will fail at compile time or behave differently at runtime. Every module
  compiles against Mutiny transitively via `quarkus-mutiny`, so the full reactor build
  is itself the first gate, followed by async/workspace-context behavior tests.
- **Jackson `2.19.2` to `2.22.0`**:
  Two minor lines of databind evolution arrive through `quarkus-rest-jackson`,
  `quarkus-jackson`, and `quarkus-config-yaml`. Coercion defaults and null handling are
  the usual risk areas; JSON round-trip tests over REST resources and LangChain4j
  guardrail payloads are the check. REST Assured 6 additionally supports Jackson 3, but
  Amsha's test assertions use `JsonPath` string/number matchers, which are unaffected.
- **jboss-logmanager pin must be resolved before Phase 1**:
  The root POM pins `org.jboss.logmanager:jboss-logmanager` `3.0.6.Final` while
  `quarkus-bom` 3.27.2 already manages `3.1.2.Final` and 3.38.3 manages `3.2.2.Final`.
  Because direct `dependencyManagement` entries outrank imported BOMs, this pin silently
  downgrades the logging manager the whole platform runs on. It must be removed or
  aligned to the platform value at the same checkpoint as each platform bump.

### 7. Test stack under JUnit 6

- **Artifact relocations (Quarkus 3.31)**:
  Per the [official guide](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31):
  `quarkus-junit5` -> `quarkus-junit`, `-component`, `-config`, `-internal`, `-mockito`,
  and `-mockito-config` follow the same pattern, and
  `io.quarkus.junit5:junit5-virtual-threads` -> `io.quarkus.junit:junit-virtual-threads`.
  Relocations keep old coordinates resolving, but Amsha should rename all declared
  coordinates (including `quarkus-junit5-internal` in `deployment`) rather than rely on
  relocation shims.
- **JUnit 6 runtime notes**:
  JUnit 6 unifies Platform/Jupiter/Vintage versions at `6.1.3`, requires Java 17,
  drops Surefire/Failsafe below 3.0 (this repository uses Surefire 3.5.x), removes
  `junit-platform-runner`/`jfr` modules, switches `@CsvSource` parsing from univocity to
  FastCSV (edge cases around quotes and line separators changed), and adopts JSpecify
  nullness annotations that may surface new IDE/tooling warnings. Existing Jupiter
  tests are expected to run largely unchanged.
- **ArchUnit requires an upgrade and artifact switch**:
  `archunit-junit5` is compiled against JUnit 5/Platform 1.x;
  [issue #1556](https://github.com/TNG/ArchUnit/issues/1556) tracked JUnit 6 support and
  ArchUnit `1.5.0` now ships an `archunit-junit6` module that the
  [user guide](https://www.archunit.org/userguide/html/000_Index.html) recommends for
  JUnit 6 environments. Amsha must bump `archunit.version` to `1.5.0` and switch the
  managed/test dependency from `archunit-junit5` to `archunit-junit6` when the reactor
  moves past JUnit 5.
- **Testcontainers 2 in `persistence-postgres` tests**:
  Artifacts gain a `testcontainers-` prefix (`postgresql` -> `testcontainers-postgresql`)
  and specialized container classes move packages
  (`org.testcontainers.containers.PostgreSQLContainer` ->
  `org.testcontainers.postgresql.PostgreSQLContainer`; the old locations remain as
  deprecated shims in 2.x). `PostgresTestResource` uses `PostgreSQLContainer`,
  `DockerClientFactory`, and `Wait`: update imports and the artifact name explicitly
  rather than depending on shims.
- **REST Assured `5.5.6` to `6.0.1`**:
  RA 6 raises the baseline to Java 17, moves to Groovy 5, and fully migrates json-path
  evaluation into Java. Plain `given()/when()/then()` usage in QuarkusTest classes is
  source-compatible; the Spring MockMvc modules' Jakarta issues do not apply because
  Amsha never uses them.
- **WireMock and embedded PostgreSQL**:
  `wiremock-standalone` shades its stack, minimizing interaction risk under the new
  Surefire/JUnit combination; `io.zonky.test:embedded-postgres` is independent of
  Quarkus entirely. Both stay pinned, but their suites still run inside the full JVM
  gate so regressions would surface there.

## Quarkus-supported update methodology

The official front doors are the latest Quarkus CLI and the versioned Quarkus
Maven plugin:

```bash
quarkus update

./mvnw io.quarkus.platform:quarkus-maven-plugin:3.38.3:update -N
```

A stream can be selected with `--stream=3.33` in the CLI or `-Dstream=3.33` in
Maven. The Maven goal also accepts an explicit `-DplatformVersion`, which is
preferable for reproducible migration checkpoints. The 3.38.3 implementation
also exposes `-DrewriteDryRun=true`, `-Drewrite=true|false`,
`-DquarkusUpdateRecipes`, and `-DadditionalUpdateRecipes`.

Sources:

- [Official update procedure](https://quarkus.io/guides/update-quarkus#procedure)
- [Quarkus 3.38.3 `UpdateMojo` parameters](https://github.com/quarkusio/quarkus/blob/3.38.3/devtools/maven/src/main/java/io/quarkus/maven/UpdateMojo.java)

The updater does more than replace one property:

1. It resolves the current project state and the target extension catalog.
2. It computes the catalog's recommended platform and extension state.
3. It selects core recipes for every crossed version boundary.
4. It selects extension-specific recipes only for matching extension
   dependencies and version transitions.
5. It generates one composite OpenRewrite recipe.
6. It can run OpenRewrite, run it in dry-run mode, or stop after analysis.

The official recipe repository states that recipes are selected by comparing
major/minor boundaries: a recipe applies when `currentVersion < recipeVersion`
and `targetVersion >= recipeVersion`. A direct 3.27-to-3.38 request therefore
selects each available core recipe from 3.28 through 3.38; staging does not
skip the official recipes.

Sources:

- [Quarkus updater command handler](https://github.com/quarkusio/quarkus/blob/3.38.3/independent-projects/tools/devtools-common/src/main/java/io/quarkus/devtools/commands/handlers/UpdateProjectCommandHandler.java)
- [Quarkus recipe selection implementation](https://github.com/quarkusio/quarkus/blob/3.38.3/independent-projects/tools/devtools-common/src/main/java/io/quarkus/devtools/project/update/rewrite/QuarkusUpdatesRepository.java)
- [Quarkus update-recipes repository and selection rules](https://github.com/quarkusio/quarkus-updates)

An updater analysis performed against this reactor for 3.38.3 recommends using
the target platform member BOMs `quarkus-camel-bom` and
`quarkus-mcp-server-bom`. That recommendation should be evaluated before
preserving the repository's current independent Camel and MCP version pins: a
platform member BOM narrows version-skew risk and allows the Quarkus catalog to
recommend a coherent set. The exact resulting dependency graph still needs to
be recorded and compared because JDBI and SQLite remain independent extension
controls.

## Is direct OpenRewrite integration possible?

Yes, but it should not be the primary integration for this migration.
OpenRewrite supports running recipes with its Maven or Gradle plugin, and the
Quarkus updater itself invokes those plugins. Quarkus's official recipe
artifact is `io.quarkus:quarkus-update-recipes`; additional recipe artifacts
can be supplied to the updater.

Primary sources:

- [OpenRewrite: running recipes](https://docs.openrewrite.org/running-recipes/getting-started)
- [Quarkus update recipes](https://github.com/quarkusio/quarkus-updates)
- [Quarkus 3.38.3 Rewrite invocation](https://github.com/quarkusio/quarkus/blob/3.38.3/independent-projects/tools/devtools-common/src/main/java/io/quarkus/devtools/project/update/rewrite/QuarkusUpdateCommand.java)

The preferred arrangement is **Quarkus updater as orchestrator, OpenRewrite as
its transformation engine**. Running the Rewrite plugin directly would require
the team to reproduce Quarkus's extension-catalog resolution, recommended-state
calculation, version-boundary recipe selection, recipe/plugin compatibility,
and extension-specific recipe discovery. There is little benefit in adding a
persistent Rewrite plugin to this repository merely for a one-time framework
migration.

Direct OpenRewrite becomes useful only when:

- Amsha needs a repository-specific mechanical recipe not covered upstream;
- the recipe is tested against representative source fixtures;
- it is supplied through `-DadditionalUpdateRecipes` or run separately in a
  clearly isolated pass;
- the generated patch remains reviewable and reversible.

### Code Genome and Moderne CLI finding (2026-08-24)

The current Moderne documentation changes the repository decision that was
previously easy to miss. It announces that the Moderne CLI and new OpenRewrite
recipe releases are moving from Maven Central to the Code Genome Project Maven
repository at `https://artifacts.codegenomeproject.org/maven`. Maven Central
is the public repository operated by Sonatype; Code Genome is a separate
authenticated repository that applies entitlement checks. It is not a general
replacement for Maven Central or a source for arbitrary application
dependencies.

This does not change the Quarkus migration choice. The official Quarkus
updater remains the orchestrator and the documented Quarkus recipe artifact is
`io.quarkus:quarkus-update-recipes`. Do not add Code Genome credentials simply
because Quarkus uses OpenRewrite. Add the repository only when the exact
Moderne/OpenRewrite CLI or recipe artifact being used resolves from Code
Genome, or when the build explicitly chooses that repository.

The Maven configuration has two separate parts: a repository URL and a
credential whose `<id>` matches it. A `<server id="codegenome">` block alone
has no effect. If Code Genome is needed, keep the credential in user-level or
CI settings (or behind an Artifactory/Nexus mirror), never in the project POM,
source control, or build logs. The token is an artifact-download credential,
not proof that the Quarkus updater itself requires a paid service.

The Moderne CLI is free for installation and public Code Genome search.
Open-source recipe access is available to authenticated users, while private
repositories require a CLI license and MSAL/proprietary recipes require the
corresponding Moderne customer entitlement. Personal public/open-source use
should use a personal account and token; an office token or office-managed
configuration must not be reused for personal work without permission. Any
token exposed in chat, shell history, source, or CI logs must be revoked and
rotated.

Primary sources for this finding:

- [Code Genome CLI](https://codegenomeproject.org/cli)
- [Accessing the Code Genome Project](https://docs.moderne.io/administrator-documentation/moderne-platform/how-to-guides/accessing-the-code-genome-project)
- [Moderne CLI license](https://docs.moderne.io/user-documentation/moderne-cli/getting-started/moderne-cli-license)
- [Quarkus update guide](https://quarkus.io/guides/update-quarkus)

### Tooling evaluation: Moderne CLI vs direct Maven rewrite plugin

Both routes were evaluated live against this reactor (2026-08-24) for the two
migration recipes the Quarkus updater does not auto-apply:
`JUnit5to6Migration` and `Testcontainers2Migration`
(`org.openrewrite.recipe:rewrite-testing-frameworks`).

- **Moderne CLI (`mod` 4.6.4)**: tenant connection, marketplace install, and
  LST build all work locally (~2 minutes for the reactor). Execution is gated,
  however: these recipes carry the Moderne Source Available License, and the
  CLI enforces it per repository — private repositories require a license key
  (`mod config license edit`). Without one, `mod run` fails with
  "A valid license is required." Also note `mod build` identifies repositories
  by their git `origin`; a local copy without a remote discovers as 0 repos.
- **Direct Maven rewrite plugin (`org.openrewrite.maven:rewrite-maven-plugin`,
  6.46.1)**: runs the identical recipes with **no license or authentication**.
  The artifact is freely resolvable from Maven Central, and OpenRewrite's
  runtime enforces nothing. Verified end-to-end with
  `dryRun` + `-Drewrite.recipeArtifactCoordinates=org.openrewrite.recipe:rewrite-testing-frameworks:3.44.0`
  + `-Drewrite.activeRecipes=...Testcontainers2Migration`: produced exactly the
  expected changes (TC2 artifact rename plus package-relocated imports), with
  one review item — the recipe adds an explicit `<version>` the platform BOM
  already manages.

Decision: keep the Quarkus updater as orchestrator for platform moves, and use
the direct Maven plugin (goal `dryRun`, then human-reviewed `apply`) for the
source-available testing recipes during Issue 04. The Moderne CLI remains
parked until there is a fleet-scale or recurring-refactor justification that
justifies a license.

### Automation limits

The official Quarkus guide gives two explicit reasons expected changes can be
missing: there may be no recipe for a project-specific item, or a project
extension may be incompatible with the latest Quarkus version. It also says the
update command automates only a subset of migration and requires manual review
of migration guides. The feature and Maven goal are marked experimental.

Consequently, neither Quarkus Update nor direct Rewrite proves:

- compatibility of Amsha's custom augmentation/build-item code;
- runtime correctness of direct Arc implementation-type usage;
- compatibility of every independently versioned Quarkiverse extension;
- profile-specific YAML behavior across `cli`, `dev`, `test`, `staging`,
  `prod`, `postgresql`, and `filesystem`;
- native reflection/resource reachability or ONNX behavior;
- transaction/threading semantics, security ordering, or metrics semantics;
- database migration or rollback safety.

The generated patch is a starting artifact, not the migration acceptance
criterion.

## Intervening migration-guide review

The following matrix filters the official 3.28-3.38 guides through this
repository's dependency and implementation surface. It is not a substitute for
reading each full guide during execution.

| Stream | Relevant official changes | Amsha implication |
| --- | --- | --- |
| [3.28](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.28) | Removal of deprecated SmallRye GraphQL client TLS configuration. | No direct GraphQL extension was found; confirm the resolved graph does not introduce one. |
| [3.29](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.29) | Qute render methods now wrap original runtime failures in `TemplateException`; ORM/reactive/search activation becomes more fail-fast. | Qute prompt/template error assertions and startup profiles need review. Hibernate is present in profile-specific app dependencies. |
| [3.30](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.30) | Removes `quarkus.datasource.jdbc.enable-metrics`; moves `CacheManagerInfoBuildItem`; changes OpenTelemetry semantic attributes and deprecated names. | Current YAML appears to use the newer metrics shape and the custom deployment code does not reference that cache build item, but metrics/tracing dashboards and assertions need semantic regression checks. |
| [3.31](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31) | Java 25 support; Surefire/Failsafe `@{argLine}` changes; optional new `quarkus` packaging/lifecycle; JUnit 6; Testcontainers 2; TLS 1.3 default; Hibernate ORM 7.2; JDBC pool default rises from 20 to 50; security interceptors run before transaction interceptors. | This is the largest checkpoint. The root already has custom Surefire/Failsafe `argLine` values and Java-agent/module flags. Keep existing `jar`/`fast-jar` packaging initially because Quarkus says the new lifecycle is optional and deliberately not auto-migrated. Exercise all 149 `@QuarkusTest` classes, DB limits, outbound TLS, and authorization/transaction tests. |
| [3.32](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.32) | Lists of `MultiBuildItem` consumed by extension build steps become immutable; datasource Dev Services SPI changes. | Compile and run augmentation tests for the custom deployment extension first. No mutation of a consumed build-item list was found in the initial scan, but this is a semantic extension API boundary. |
| [3.33](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.33) | No application migration items are currently listed. | Use 3.33.3.1 as the LTS stabilization checkpoint after resolving the 3.31/3.32 changes. |
| [3.34](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.34) | Micrometer Prometheus now throws when the same metric is registered with inconsistent tag sets instead of silently dropping the later registration. | Audit custom meters and run metric startup/runtime tests; the repository exposes Micrometer/Prometheus broadly. |
| [3.35](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.35) | Quarkus REST no longer treats `@Transactional` alone as a blocking marker; REST Assured moves to 6; Hibernate ORM moves to 7.3; Micrometer behavior above is reiterated. | Revalidate REST thread placement, especially the repository's custom `@RunOnVirtualThread` enforcement and actual-thread event-loop guards. Search found no obvious REST class combining `@Path` and `@Transactional`, but augmentation and runtime tests remain required. |
| [3.36](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.36) | OpenTelemetry exporter/config changes and new `other` keystore/truststore type. | Re-run telemetry exporter and TLS configuration tests; inspect removed/deprecated OTel usage in resolved providers. |
| [3.37](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.37) | Hibernate ORM 7.4, DB/Dev Services changes, and Arc now applies method-level interceptor bindings on default interface methods. | Run all persistence backends and review default interface methods carrying `@Transactional`, security, or other interceptor bindings. |
| [3.38](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.38) | Removes deprecated OTel `LateBoundSpanProcessor`; changes integration-test configuration precedence; enables localhost Host-header validation when `quarkus.http.host` is localhost. | No `@QuarkusIntegrationTest` classes were found, so Amsha's own runtime smoke remains essential. Check reverse-proxy and localhost smoke behavior and any OTel customizer implementation. |

GraalVM/Mandrel 25 also changes native resource metadata behavior: the Quarkus
native guide marks resource exclusion configuration deprecated from 3.29
because exclusions are unsupported in the new `reachability-metadata.json`
format used by Mandrel/GraalVM 25 onward. The initial scan found no active
resource-excludes property, but all custom reflection/resource build items must
still be tested in real native builds.

Source: [Quarkus native-image guide](https://quarkus.io/guides/building-native-image).

## Extension compatibility

Extension alignment is a release gate, not a cleanup item after compilation.
There are six version authorities today: Quarkus platform,
Quarkus LangChain4j, Camel Quarkus, JDBI, MCP, and SQLite. A coherent target must be written down before
applying recipes.

At the latest-target checkpoint:

- Quarkus platform and plugin should be 3.38.3.
- The target Quarkus catalog's member BOM recommendations for Camel and MCP
  should be preferred unless dependency analysis proves a reason not to use
  them.
- Apache Camel Quarkus 3.38.0 is the latest observed Camel Quarkus release and
  its official POM targets Quarkus 3.38.0, which is the matching minor stream;
  same-stream 3.38.3 compatibility must still be demonstrated by the reactor
  tests.
- JDBI and SQLite extension versions must be checked in the 3.38.3 extension
  catalog or against their first-party release metadata; absence from the
  catalog is a stop condition for blind updating, not permission to retain an
  old version.
- Generate and compare dependency trees for each checkpoint, looking for
  multiple Arc, Vert.x, Netty, Jackson, JUnit, Testcontainers, OpenTelemetry,
  Graal SDK, and Jakarta API versions.

Primary source for Camel alignment:

- [Apache Camel Quarkus 3.38.0 release](https://github.com/apache/camel-quarkus/releases/tag/3.38.0)
- [Apache Camel Quarkus 3.38.0 POM (`quarkus.version` 3.38.0)](https://github.com/apache/camel-quarkus/blob/3.38.0/pom.xml)

## Recommended staged execution

### Phase 0: make the baseline reproducible

1. Start from a human-created migration branch or isolated worktree with no
   unrelated changes. Do not combine product features with this migration.
2. Record the current JDK, Maven wrapper, GraalVM/Mandrel, platform BOMs,
   effective POM, dependency trees, generated extension list, test counts,
   runtime smoke results, and native artifact hashes.
3. Run the repository's current required formatting, PMD, focused test, full
   JVM, persistence-backend, and native smoke gates. A failing baseline must be
   resolved or explicitly recorded before attributing failures to Quarkus.
4. Upgrade the Maven wrapper from 3.9.12 to the documented 3.9.16 prerequisite
   as an isolated build-tool change; repeat the JVM build gate.

### Phase 1: current LTS patch, 3.27.2 to 3.27.5.1

1. Run updater analysis/dry-run with an explicit target:

   ```bash
   ./mvnw io.quarkus.platform:quarkus-maven-plugin:3.38.3:update -N \
     -DplatformVersion=3.27.5.1 -DrewriteDryRun=true
   ```

2. Review the generated recipe, patch, updater log, BOM recommendations, and
   dependency changes. Never apply an updater patch sight unseen.
3. Apply only the same-stream changes, align matching platform members, and
   keep application behavior unchanged.
4. Run custom deployment augmentation, focused module tests, all JVM tests,
   SQLite and PostgreSQL flows, native SQLite smoke, and at least one full
   native build. This phase proves the CVE/native-regression maintenance update
   without cross-minor changes.
5. The human reviewer creates the checkpoint commit/tag only after the gates
   pass.

### Phase 2: latest LTS checkpoint, 3.27.5.1 to 3.33.3.1

1. Generate a new dry-run against explicit `3.33.3.1`.
2. Review all 3.28-3.33 migration-guide items, with 3.31 treated as its own
   diagnostic sub-checkpoint if failures are numerous.
3. Keep the current `jar`/`fast-jar` lifecycle. Do not adopt the optional
   `quarkus` packaging optimization in the same change.
4. Fix the Surefire/Failsafe late `argLine` composition while preserving the
   Byte Buddy agent, module opens, dynamic-agent flags, and native access.
5. Compile and test `core`/`deployment` first; then affected modules; then the
   entire reactor. Exercise TLS 1.3, connection-pool limits, security/transaction
   ordering, Qute error paths, JUnit 6, and Testcontainers 2.
6. Run all three persistence backends and the proportional native matrix.
7. Create a separately deployable 3.33.3.1 artifact and soak it before deciding
   to advance.

### Decision gate: stop at LTS or continue to latest

Choose 3.33.3.1 for production when stability/support horizon is more valuable
than a specific post-3.33 capability. Continue only when the team accepts the
regular-stream cadence or has a concrete need for 3.34-3.38.

### Phase 3: latest stable, 3.33.3.1 to 3.38.3

1. Generate and review a fresh 3.38.3 dry-run.
2. Prefer catalog-recommended Camel and MCP member BOMs; resolve JDBI and SQLite
   compatibility before compilation.
3. Review 3.34-3.38 manual changes: meter tag consistency, REST
   transaction/thread placement, ORM 7.3/7.4, Arc default-method interception,
   OTel removals, and Host-header validation.
4. Run the custom extension augmentation gates first, then 149 `@QuarkusTest`
   classes and profile-specific tests.
5. Run JVM validation for SQLite, PostgreSQL, and filesystem backends.
6. Build and smoke the SQLite native binary, then build PostgreSQL and
   filesystem native variants, then run the repository's three-platform ONNX
   release evidence. Validate reflection, resources, startup logs, telemetry,
   approval flows, Telegram, MCP, and shutdown behavior.
7. Update version-bearing documentation/fixtures only after the executable
   migration is stable, then run the documentation verifier.
8. Soak/canary the exact native artifacts that will be promoted.

## Rollback design

Each checkpoint must produce a deployable artifact and must remain independently
revertible. Rollback should select the previously validated artifact and its
configuration, not attempt an in-place reverse OpenRewrite run.

Framework migration changes should not include irreversible database schema or
data changes. If a schema change becomes unavoidable, use an expand/contract
migration compatible with both the old and new application, back up the
database, and prove old-artifact startup before promotion. Never rely on Flyway
down-migration as the sole rollback mechanism.

Preserve for every checkpoint:

- source revision and reviewed updater patch;
- JDK/Maven/GraalVM or Mandrel versions;
- platform/member BOM matrix and dependency trees;
- JVM and each native artifact digest;
- configuration and secret-schema version, without recording secrets;
- Flyway schema history and database backup reference;
- test, smoke, PMD, native matrix, and release-evidence results;
- canary health, error, latency, memory, metric, and trace baselines.

Rollback triggers should be agreed before deployment: failed startup or
augmentation, reflection/resource errors, persistence incompatibility,
authorization/transaction semantic regressions, event-loop blocking failures,
metric registration exceptions, telemetry loss, or material latency/memory
regression. For database-compatible checkpoints, rollback is artifact/config
selection plus health verification. For any checkpoint that changes external
protocol behavior, retain compatibility or coordinate the consumer rollback in
the same deployment plan.

## Acceptance criteria

The upgrade is complete only when:

- the selected target and all member/independent extension versions are
  documented and resolve without skew;
- updater output and every intervening migration guide have been reviewed;
- the custom `deployment` extension compiles, augments, and passes its tests;
- required formatting, PMD, focused, full JVM, and documentation checks pass;
- all persistence backends pass their migrations and runtime tests;
- native SQLite smoke, PostgreSQL/filesystem native builds, and the applicable
  release/native matrix pass;
- security, transaction/threading, approval, telemetry, Qute, metrics, MCP,
  Telegram, and provider flows show no semantic regression;
- the exact promoted artifact has completed the agreed canary/soak period;
- the previous checkpoint remains deployable and its rollback has been
  rehearsed or mechanically verified.

## Primary-source index

- [Latest Quarkus release](https://github.com/quarkusio/quarkus/releases/latest)
- [Quarkus 3.38.3 release note](https://quarkus.io/blog/quarkus-3-38-3-released/)
- [Quarkus Update guide](https://quarkus.io/guides/update-quarkus)
- [Quarkus migration-guide index](https://github.com/quarkusio/quarkus/wiki/Migration-Guides)
- [Quarkus community LTS policy](https://quarkus.io/blog/lts-releases/)
- [Quarkus 3.27.5.1 LTS emergency release](https://quarkus.io/blog/quarkus-3-27-5-1-released/)
- [Quarkus 3.33.3.1 LTS emergency release](https://quarkus.io/blog/quarkus-3-33-3-1-released/)
- [Quarkus update recipes](https://github.com/quarkusio/quarkus-updates)
- [Quarkus 3.38.3 Maven update goal source](https://github.com/quarkusio/quarkus/blob/3.38.3/devtools/maven/src/main/java/io/quarkus/maven/UpdateMojo.java)
- [Quarkus 3.38.3 updater orchestration source](https://github.com/quarkusio/quarkus/blob/3.38.3/independent-projects/tools/devtools-common/src/main/java/io/quarkus/devtools/commands/handlers/UpdateProjectCommandHandler.java)
- [Quarkus 3.38.3 recipe selection source](https://github.com/quarkusio/quarkus/blob/3.38.3/independent-projects/tools/devtools-common/src/main/java/io/quarkus/devtools/project/update/rewrite/QuarkusUpdatesRepository.java)
- [Quarkus native-image guide](https://quarkus.io/guides/building-native-image)
- [OpenRewrite recipe runner](https://docs.openrewrite.org/running-recipes/getting-started)
- [Apache Camel Quarkus 3.38.0](https://github.com/apache/camel-quarkus/releases/tag/3.38.0)
- [Quarkiverse MCP Server Extension](https://github.com/quarkiverse/quarkus-mcp-server)
- [`quarkus-mcp-server-bom` 3.38.3 POM (pins `1.13.1`)](https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-mcp-server-bom/3.38.3/quarkus-mcp-server-bom-3.38.3.pom)
- [quarkus-mcp-server 1.13.0 release notes (behavior carried into the pinned 1.13.1 patch)](https://github.com/quarkiverse/quarkus-mcp-server/releases/tag/1.13.0)
- [Camel Quarkus 3.31.0 migration guide (junit5 -> junit rename, removal in 3.34)](https://camel.apache.org/camel-quarkus/next/migration-guide/3.31.0.html)
- [`io.quarkus:quarkus-bom` 3.27.2 POM](https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.27.2/quarkus-bom-3.27.2.pom)
- [`io.quarkus:quarkus-bom` 3.38.3 POM](https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.38.3/quarkus-bom-3.38.3.pom)
- [`quarkus-langchain4j-bom` 3.27.2 POM](https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-langchain4j-bom/3.27.2/quarkus-langchain4j-bom-3.27.2.pom)
- [`quarkus-langchain4j-bom` 3.38.3 POM](https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-langchain4j-bom/3.38.3/quarkus-langchain4j-bom-3.38.3.pom)
- [LangChain4j `ChatModel` javadoc (`chat(ChatRequest)`, `ChatRequestOptions` since 1.13.0)](https://docs.langchain4j.dev/apidocs/dev/langchain4j/model/chat/ChatModel.html)
- [Mutiny release history (3.x line)](https://github.com/smallrye/smallrye-mutiny/releases)
- [REST Assured 6.0.0 release notes (Java 17 baseline, Groovy 5)](https://github.com/rest-assured/rest-assured/wiki/ReleaseNotes60)
- [JUnit 6 upgrade notes (unified versions, removed modules, FastCSV)](https://github.com/junit-team/junit-framework/wiki/Upgrading-to-JUnit-6.0/How-tos)
- [Testcontainers 2 migration (artifact/package renames)](https://mvnrepository.com/artifact/org.testcontainers/testcontainers-postgresql/2.0.0)
- [ArchUnit JUnit 6 support issue #1556 (`archunit-junit6` in 1.5.0)](https://github.com/TNG/ArchUnit/issues/1556)
- [ArchUnit user guide (archunit-junit6 module)](https://www.archunit.org/userguide/html/000_Index.html)
- [Byte Buddy compatibility table (Java 25 support since 1.17.0)](https://github.com/raphw/byte-buddy)
- [Flyway engine release notes (12.x breaking changes)](https://documentation.red-gate.com/flyway/release-notes-and-older-versions/release-notes-for-flyway-engine)
- [Red Hat Build of Quarkus 3.33 migration guide (cross-confirms 3.31 items)](https://docs.redhat.com/en/documentation/red_hat_build_of_quarkus/3.33/html-single/migrate_applications_to_3.33/index)
- [Quarkiverse JDBI Extension](https://github.com/quarkiverse/quarkus-jdbi)
- [Quarkiverse SQLite JDBC Extension](https://github.com/quarkiverse/quarkus-jdbc-sqlite)
- [DJL HuggingFace Tokenizers](https://github.com/deepjavalibrary/djl/tree/master/extensions/tokenizers)
- [ONNX Runtime Java API](https://onnxruntime.ai/docs/api/java/)
- [Apache Lucene 10 Releases](https://lucene.apache.org/core/)
- [Bouncy Castle Crypto Packages](https://www.bouncycastle.org/)
- [Bucket4j Rate Limiting](https://bucket4j.com/)
