# ADR: Stage the Quarkus 3.27 to 3.38 Upgrade Through Supported Checkpoints

- **Status**: Accepted and Implemented — Quarkus 3.38.3 Latest Stream upgrade completed across all 29 modules and 3 persistence backends (SQLite, PostgreSQL, Filesystem); full release evidence and soak verification recorded.
- **Date**: 2026-08-23 (Updated 2026-08-25)
- **Decision owners**: Amsha Agent maintainers
- **Baseline platform**: Quarkus `3.27.2`
- **Current platform**: Quarkus `3.38.3`
- **Latest stable at review time**: Quarkus `3.38.3`
- **Checkpoint releases**: Quarkus `3.27.5.1` and `3.33.3.1` LTS
- **Research**: [Quarkus 3.38 Upgrade Research](Quarkus-Upgrade-Research.md)
- **Related invariants**: [Architecture Invariants](../../docs/Architecture_Invariants.md)

## 1. Decision summary

This decision records Amsha's migration from Quarkus `3.27.2` to the latest stable Quarkus release
using three independently validated checkpoints:

1. `3.27.2` to `3.27.5.1`, the latest patch in the current LTS stream;
2. `3.27.5.1` to `3.33.3.1`, the newest supported LTS stream; and
3. `3.33.3.1` to `3.38.3`, the latest stable feature stream as of
   2026-08-23.

The `3.33.3.1` checkpoint is a production-capable decision gate, not merely a
temporary build. Maintainers may stop there when the post-3.33 features do not
justify the shorter non-LTS update cadence. Continuing to `3.38.3` requires all
3.33 gates to pass first and a separate review of the 3.34-3.38 migration
guides.

The official Quarkus Maven `update` goal will be the migration orchestrator. It
uses the Quarkus extension catalog to recommend a coherent platform and
selects official OpenRewrite recipes for every crossed version boundary. It
will first be run with rewriting disabled and then in OpenRewrite dry-run mode.
The generated patch is a proposal for human review; it is not an acceptance
test and will never be applied sight unseen.

Amsha will align Camel Quarkus and MCP Server through the corresponding Quarkus
platform member BOMs at each cross-stream checkpoint. JDBI and SQLite JDBC will
remain explicitly versioned because they are independently released
Quarkiverse extensions. Every checkpoint must retain a single Quarkus platform
version, preserve the existing `${quarkus.platform.version}` alias, and prove
JVM, persistence, native-image, security, threading, metrics, and telemetry
behavior before promotion.

## 2. Context

The root build currently compiles with Java 25 and controls the following
framework families independently:

| Family / Component | Current control | Current version | Target in 3.38.3 platform | Control mechanism |
| --- | --- | --- | --- | --- |
| Quarkus core platform | `io.quarkus.platform:quarkus-bom` | `3.27.2` | `3.38.3` | Quarkus Platform BOM |
| Quarkus LangChain4j platform | `io.quarkus.platform:quarkus-langchain4j-bom` | `3.27.2` | `3.38.3` | Quarkus LangChain4j Member BOM |
| Camel Quarkus | `org.apache.camel.quarkus:camel-quarkus-bom` | `3.27.3` | `3.38.0` | Switch to `io.quarkus.platform:quarkus-camel-bom` |
| Quarkiverse MCP Server | Direct dependency versions | `1.10.3` | `1.13.1` | Switch to `io.quarkus.platform:quarkus-mcp-server-bom` |
| Quarkiverse JDBI | Direct dependency version | `1.9.0` (Jdbi `3.50.0`) | `1.10.1` (Jdbi `3.54.0`) | Pinned Quarkiverse property |
| Quarkiverse SQLite JDBC | Direct dependency version | `3.0.11` | `3.0.11` (latest published) | Pinned Quarkiverse property |
| LangChain4j libraries | Managed by member BOM | `1.5.0` | `1.17.2` | Managed entirely by LangChain4j BOM |
| Stale `<langchain4j.version>` | Root property | `1.0.0-beta1` | *Removed* (unused) | No module references it |
| PostgreSQL JDBC Driver | Managed by `quarkus-bom` | `42.7.7` | `42.7.13` | Managed by Quarkus Platform BOM |
| Flyway Migration | Managed by `quarkus-bom` | `11.11.2` | `12.0.0` | Major bump via platform BOM |
| OpenInference LangChain4j | Direct dependency version | `0.1.8` | `0.1.8` | Direct pin; declared, not referenced in code |
| DJL HuggingFace Tokenizers | Direct dependency version | `0.33.0` | `0.33.0` | Direct pin; offline enforced |
| ONNX Runtime | Direct dependency version | `1.27.0` | `1.27.0` | Direct pin; single-threaded native mode |
| Apache Lucene | Direct dependency version | `10.4.0` | `10.4.0` (Pinned) | Pinned to match native codec reflection |
| Bucket4j | Direct dependency version | `8.16.1` | `8.16.1` | Pinned; lock-free CAS token bucket |
| ArchUnit | Root pin | `archunit-junit5` `1.4.1` | `archunit-junit6` `1.5.0` | Required switch for the JUnit 6 engine |
| WireMock / embedded-postgres | Test-scope pins | `3.9.2` / `2.2.2` | unchanged | Independent of Quarkus; covered by JVM gates |
| Error Prone Core | Direct dependency version | `2.49.0` | `2.49.0` | Compiler plugin annotation processor |
| Maven PMD Plugin | Direct plugin version | `3.28.0` | `3.28.0` | Build plugin with PMD 7 engine |
| Maven Wrapper | Wrapper properties | `3.9.12` | `3.9.16` | Toolchain prerequisite |

Four root-POM pins override the imported platform BOM because direct
`dependencyManagement` entries outrank BOM imports, and two are already below
what even today's 3.27.2 platform manages:

| Root pin | Pinned value | Managed at 3.27.2 | Managed at 3.38.3 |
| --- | --- | --- | --- |
| `bytebuddy.version` (test dep + javaagent path) | `1.17.6` | `1.17.6` | `1.18.8` |
| `jboss-logmanager.version` (+ inherited test dep) | `3.0.6.Final` | `3.1.2.Final` | `3.2.2.Final` |
| `bouncycastle.version` (`bcprov-jdk18on`) | `1.78.1` | `1.82` | `1.84` |
| `commons-lang3.version` | `3.18.0` | `3.18.0` | `3.20.0` |

The jboss-logmanager and Bouncy Castle pins silently downgrade libraries the
platform itself runs on today; that is a live defect, not only a migration risk.
Every pin must be aligned to the checkpoint's platform value or carry a written
justification in the migration change.

The platform also moves every BOM-managed family underneath the application;
the [research note](Quarkus-Upgrade-Research.md#verified-platform-managed-version-moves-quarkus-bom-diff)
records values extracted from the published BOMs. The largest moves are Mutiny
`2.9.5` -> `3.3.0`, Jackson `2.19.2` -> `2.22.0`, Micrometer `1.14.7` ->
`1.17.0`, OpenTelemetry SDK `1.44.1` -> `1.62.0`, Hibernate ORM `7.1.14.Final`
-> `7.4.5.Final`, Agroal `2.8` -> `3.2.1`, Flyway `11.11.2` -> `12.0.0`, REST
Assured `5.5.6` -> `6.0.1`, JUnit `5.13.4` -> `6.1.3`, Testcontainers `1.21.4`
-> `2.0.5`, Vert.x `4.5.23` -> `4.5.32`, Netty `4.1.130.Final` ->
`4.1.137.Final`, and SmallRye OpenAPI/Health/Fault Tolerance/Context-Propagation
maintenance trains. These library upgrades cannot be declined independently:
retaining old versions while bumping Quarkus is exactly the skew this decision
forbids.

This is not a normal single-module application upgrade. The reactor contains
28 Maven modules plus the root aggregator — 29 projects in the observed dry
run — including a custom Quarkus `deployment` module. That module consumes augmentation APIs such as
`ContextRegistrationPhaseBuildItem`, `CustomScopeBuildItem`,
`UnremovableBeanBuildItem`, `ReflectiveClassBuildItem`, and
`NativeImageResourceBuildItem`. Core code also references the Arc
implementation marker `io.quarkus.arc.Subclass`. Compilation and augmentation
tests are therefore required even when OpenRewrite reports no Java source
changes.

Quarkus `3.38.3` is the latest stable community release as of this decision.
Quarkus `3.33` and `3.27` are the supported LTS streams. The community LTS
policy provides approximately 12 months of critical fixes and plans a new LTS
about every six months. A move to `3.38.3` gains the newest platform but also
accepts the regular-stream maintenance obligation. See the
[official update guide](https://quarkus.io/guides/update-quarkus),
[security support table](https://quarkus.io/security/), and
[LTS policy](https://quarkus.io/blog/lts-releases/).

The current Maven wrapper is `3.9.12`, while the Quarkus 3.38 update guide lists
Maven `3.9.16` as a prerequisite. Toolchain alignment must precede framework
changes so updater and build failures are not conflated.

## 3. Evidence from the repository dry run

On 2026-08-23, the Quarkus `3.38.3` updater was run against `app/pom.xml` with
rewriting disabled and then with `-DrewriteDryRun=true`. The dry run changed no
tracked files and completed successfully across the complete reactor.

The generated OpenRewrite proposal touched 25 POMs with 49 insertions and 78
deletions. It correctly proposed, among other changes:

- Quarkus platform `3.27.2` to `3.38.3`;
- JDBI `1.9.0` to `1.10.1` for the 3.38 target;
- Quarkus 3.31 JUnit artifact relocations, such as `quarkus-junit5` to
  `quarkus-junit` and `quarkus-junit5-mockito` to
  `quarkus-junit-mockito`;
- late Surefire/Failsafe `argLine` composition;
- no change to the existing external `camel-quarkus-bom` import or the direct
  MCP and Camel version properties; and
- core recipes for the 3.30, 3.31, 3.37, and 3.38 boundaries plus Camel
  recipes through Camel 4.21.

The same run demonstrated important automation limits:

- Running the documented `update -N` command at the aggregator root reported
  no Quarkus extensions because the root POM owns dependency management but no
  application extensions. Running against `app/pom.xml` detected the real
  extension set and still launched the OpenRewrite dry run over all reactor
  modules.
- The updater recommended adding
  `io.quarkus.platform:quarkus-camel-bom` and
  `io.quarkus.platform:quarkus-mcp-server-bom`, but the checked patch neither
  added those imports nor removed the existing controls: the external
  `camel-quarkus-bom` import and the direct `${quarkus-mcp.version}` and
  `${camel.quarkus.version}` properties remain in `pom.xml`. Adopting the
  member BOMs is therefore a manual follow-up step, and until it happens (and
  an effective POM proves a single authority per family) the reactor carries
  dual version controls whose skew must be resolved deliberately rather than
  by the patch.
- The patch replaced `${quarkus.platform.version}` in `quarkus.version` with a
  second literal, which would create avoidable version drift.
- It removed explicit versions for Byte Buddy, Bouncy Castle, Jandex, and other
  dependencies whenever a target BOM managed them. Some removals may be good,
  but every intentional security, native-access, or compatibility pin must be
  reviewed rather than discarded mechanically.
- The dry run proposed only POM changes. It cannot prove augmentation API,
  native reflection/resource, persistence, transaction/thread, authorization,
  metrics, telemetry, MCP protocol, or runtime behavior.

These findings are the main reason to use the updater as a catalog-aware patch
generator while retaining human control over the resulting dependency model.

## 4. Platform alignment policy

At the `3.33.3.1` and `3.38.3` checkpoints, the root dependency management will
prefer these platform imports at the same `${quarkus.platform.version}`:

```xml
<dependency>
  <groupId>io.quarkus.platform</groupId>
  <artifactId>quarkus-bom</artifactId>
  <version>${quarkus.platform.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
<dependency>
  <groupId>io.quarkus.platform</groupId>
  <artifactId>quarkus-langchain4j-bom</artifactId>
  <version>${quarkus.platform.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
<dependency>
  <groupId>io.quarkus.platform</groupId>
  <artifactId>quarkus-camel-bom</artifactId>
  <version>${quarkus.platform.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
<dependency>
  <groupId>io.quarkus.platform</groupId>
  <artifactId>quarkus-mcp-server-bom</artifactId>
  <version>${quarkus.platform.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

The external `org.apache.camel.quarkus:camel-quarkus-bom` import and the direct
MCP version property may be removed only after the effective POM proves the
platform member BOMs manage every used Camel and MCP artifact. For Quarkus
`3.38.3`, the published platform BOM manages Camel Quarkus `3.38.0` and MCP
Server `1.13.1`.

The following rules apply to all checkpoints:

1. `quarkus.version` remains `${quarkus.platform.version}`; there is one
   authoritative Quarkus version property.
2. The Quarkus Maven plugin uses the same platform version.
3. Explicit versions are removed only after `help:effective-pom` and dependency
   trees prove the intended managed version.
4. Remove unmanaged/dead properties such as `<langchain4j.version>1.0.0-beta1</langchain4j.version>`
   from root `pom.xml` since all LangChain4j coordinates are governed by `quarkus-langchain4j-bom`.
5. Every root-POM pin must equal the checkpoint's platform-managed value or
   carry a written justification in the migration change. At Phase 1 the
   jboss-logmanager pin (`3.0.6.Final` below the platform's `3.1.2.Final`) and
   Bouncy Castle pin (`1.78.1` below `1.82`) must be aligned; at Phase 2/3
   byte-buddy (`1.18.8`) and commons-lang3 (`3.20.0`) follow the platform.
   Byte Buddy has supported Java 25 class files since `1.17.0`, so
   `-Dnet.bytebuddy.experimental=true` is dropped once the full JVM suite passes
   without it.
6. JDBI and SQLite JDBC keep explicit properties unless they join a selected
   platform member BOM. The 3.38 updater recommends JDBI `1.10.1` (Jdbi
   `3.54.0`, built against Quarkus `3.38.0`). SQLite JDBC stays at `3.0.11`
   because that is the latest published release, not by preference; its build
   baseline (Quarkus `3.15.1`, xerial `sqlite-jdbc` `3.46.1.3`) means SQLite
   JVM plus native compatibility must be proven, never assumed.
7. Apache Lucene remains pinned at `10.4.0` to preserve binary alignment with
   exact native reflection codec registrations (`Lucene104Codec`).
8. Maven Surefire and Failsafe plugins must use late evaluation syntax `@{argLine}`
   to ensure JaCoCo coverage and Java 25 dynamic agent arguments compose correctly.
9. No transitive mix of Arc, Vert.x, Netty, Jackson, JUnit, Testcontainers,
   OpenTelemetry, Graal SDK, or Jakarta API release families is accepted.
10. The Mandrel/GraalVM builder image and its resolved digest are recorded with
    each native artifact. A floating `jdk-25` tag is not sufficient release
    evidence by itself.
11. Align every explicit `jandex-maven-plugin` version pin with the
    parent-managed `3.6.0` so extension indexing matches the platform's Jandex
    runtime line. Verified on this reactor: 20 modules pin `3.5.3` and
    `persistence-postgres` pins the older `3.1.2`; only the root POM uses the
    parent-managed version.

## 5. OpenRewrite decision

### 5.1 Selected use

Use the Quarkus updater to generate the composite recipe because it combines:

- current-project extension discovery;
- target Quarkus registry recommendations;
- version-boundary selection of Quarkus core recipes;
- extension-specific recipes, including Camel recipes; and
- the official `io.quarkus:quarkus-update-recipes` artifact.

The `quarkus-update` agent skill (installed via
`npx skills add quarkusio/skills --skill quarkus-update`) is used as the
pre-flight companion to this updater: it diffs the project's build files against
the matching `code-with-quarkus-compare` reference tag and records structural
drift as evidence before any recipe runs (see
`.scratch/quarkus-upgrade/evidence/04-phase2/skill-report.md`).

For each target, perform analysis and dry run from the application module:

```bash
./mvnw -f app/pom.xml \
  io.quarkus.platform:quarkus-maven-plugin:3.38.3:update \
  -DplatformVersion=3.33.3.1 \
  -Drewrite=false

./mvnw -f app/pom.xml \
  io.quarkus.platform:quarkus-maven-plugin:3.38.3:update \
  -DplatformVersion=3.33.3.1 \
  -DrewriteDryRun=true
```

Repeat with `3.27.5.1` and `3.38.3` at the corresponding checkpoints. Explicit
`-DplatformVersion` values are preferred over a floating stream during the
actual migration. A later maintenance workflow may use `-Dstream` to discover
new patches.

The expected review artifacts are:

- `app/target/rewrite/rewrite.yaml`;
- `app/target/rewrite/rewrite.log`; and
- `target/rewrite/rewrite.patch`.

They are generated build artifacts and are not migration evidence on their own.
Record the updater/plugin/recipe versions and reviewed patch summary in the
human-owned change review.

### 5.2 Patch review and application

Before applying a patch:

```bash
git apply --check target/rewrite/rewrite.patch
git apply --stat target/rewrite/rewrite.patch
git apply --numstat target/rewrite/rewrite.patch
```

Review the full patch, compare every removed version with the effective POM,
and list the manual corrections. Only then may the patch be applied in the
isolated migration worktree. Applying the patch does not authorize staging or
committing; those actions remain exclusively with the human maintainer under
the repository invariant.

### 5.3 Rejected permanent integration

Do not add a permanent raw `rewrite-maven-plugin` execution to the normal build
for this migration. Direct OpenRewrite is technically possible, but it omits
the Quarkus registry's recommended-state and recipe-selection orchestration and
adds build complexity after a one-time use. If the same Amsha-specific manual
rewrite recurs in at least two future upgrades, maintainers may separately
propose a pinned repository recipe and pass it through the updater's
`-DadditionalUpdateRecipes` option.

### 5.4 Repository and credential boundary

The migration does not adopt the Moderne CLI as a build dependency. The
Quarkus Maven updater remains the selected front door, with Quarkus'
`io.quarkus:quarkus-update-recipes` artifact and the project's configured Maven
repositories. A Code Genome credential is not required merely because the
migration uses OpenRewrite internally.

The current Moderne documentation announces that the Moderne CLI and new
OpenRewrite recipe releases are moving from Maven Central to the Code Genome
Project Maven repository:
`https://artifacts.codegenomeproject.org/maven`. Maven Central is the public
Sonatype-operated repository; Code Genome is a separate authenticated
repository with entitlement checks and is not a replacement for arbitrary
project dependencies.

Use Code Genome only when the exact CLI or recipe artifact being requested is
hosted there, or when the build intentionally declares that repository. A
Maven `<server>` entry by itself does nothing: its `<id>` must match a
`<repository>` or `<pluginRepository>` entry. If access is required, keep the
repository credentials in a developer `settings.xml`, an internal Artifactory
or Nexus mirror, or a CI secret store. Never put a token in a POM, commit it,
or print it in logs. Maven password encryption should be used for a local
settings file where practical.

The Moderne CLI has a narrower free boundary than the artifact repository
question: its installation and public Code Genome search are free, and
open-source recipe access is available to authenticated users. Private
repositories require a CLI license, while MSAL or proprietary recipes require
the applicable Moderne customer entitlement. Therefore the office's CLI
installation or token must not be reused for personal work without employer
permission; personal public/open-source work should use a separate personal
account and token. A credential exposed in chat, shell history, source, or CI
logs is compromised and must be revoked and rotated.

For this migration, first run the documented Quarkus updater without adding a
Code Genome repository. Add it only if dependency resolution for the selected
tool/recipe version proves that the artifact is no longer available through
the configured repositories. This keeps the migration reproducible and avoids
making a hosted service a hidden prerequisite.

Sources: [Code Genome CLI](https://codegenomeproject.org/cli), [Code Genome
access guide](https://docs.moderne.io/administrator-documentation/moderne-platform/how-to-guides/accessing-the-code-genome-project),
and [Moderne CLI license details](https://docs.moderne.io/user-documentation/moderne-cli/getting-started/moderne-cli-license).

## 6. Migration plan

### Phase 0: isolate and baseline

1. The human maintainer creates a dedicated branch and clean worktree. No
   product feature, database schema change, dependency refresh unrelated to
   Quarkus, or formatting-only cleanup is included.
2. Record the source revision and current outputs of `java --version`,
   `./mvnw --version`, `mvn --version`, the GraalVM/Mandrel version, platform
   BOMs, effective POM, dependency trees, test counts, native artifact hashes,
   and current runtime smoke results.
3. Establish a green `3.27.2` baseline using the verification matrix in
   Section 8. Pre-existing failures are resolved or explicitly waived before
   any migration result is interpreted.
4. Upgrade the Maven wrapper from `3.9.12` to `3.9.16` as a separate toolchain
   change. Confirm both `./mvnw --version` and `mvn --version` report the
   expected Maven because the updater launches a nested Maven process for
   OpenRewrite.
5. Re-run the baseline after the Maven-only change. Do not begin Phase 1 until
   it is green.

### Phase 1: patch the current LTS to 3.27.5.1

1. Run updater analysis and dry run with
   `-DplatformVersion=3.27.5.1`.
2. Review the current-stream release notes and patch. This checkpoint includes
   security and native-image fixes, including the Quarkus REST multipart issue
   fixed by CVE-2026-16308 releases, and delivers PostgreSQL driver `42.7.13`.
3. Reconcile the two pins that today sit below what even 3.27.2 manages:
   align `jboss-logmanager.version` to `3.1.2.Final` (or drop the pin) and
   `bouncycastle.version` to `1.82`, then re-validate `amsha-security` native
   reflection partitions against the resolved jars.
4. Preserve the existing dependency-management shape otherwise. Do not combine
   the member BOM restructuring with this patch-only checkpoint.
5. Compile `deployment` and `app` first, then the affected dependency graph.
6. Run focused tests, all JVM tests, all persistence backend builds, the
   SQLite native build and smoke, and at least one complete native-image gate.
7. Produce a deployable artifact and retain it as the first rollback boundary.
   The human maintainer owns review, staging, and the checkpoint commit.

### Phase 2: move to the 3.33.3.1 LTS checkpoint

1. Start from the green `3.27.5.1` checkpoint and generate a new updater
   analysis and dry-run patch for `3.33.3.1`.
2. Review every official 3.28-3.33 migration guide. Treat 3.31 as a diagnostic
   sub-checkpoint because it changes Java 25 support, JUnit, Testcontainers,
   Surefire/Failsafe behavior, TLS defaults, Hibernate ORM, and packaging
   details.
3. Add the Quarkus platform Camel (`quarkus-camel-bom`) and MCP (`quarkus-mcp-server-bom`)
   member BOMs, then remove the external Camel BOM and direct MCP versions only after
   effective-POM verification.
4. Remove the unmanaged `<langchain4j.version>` property from root `pom.xml`.
5. Preserve `${quarkus.platform.version}` as the `quarkus.version` value.
6. Adopt the Maven Surefire/Failsafe late evaluation syntax `@{argLine}` across root
   `pom.xml` and `deployment/pom.xml` together with the existing JaCoCo, Byte Buddy,
   module-open, native-access, and dynamic-agent arguments (`-XX:+EnableDynamicAgentLoading`).
   Prove that all agent arguments survive in the effective Surefire execution.
7. Apply the Quarkus JUnit artifact relocations across every module:
   - `io.quarkus:quarkus-junit5` -> `io.quarkus:quarkus-junit`
   - `io.quarkus:quarkus-junit5-mockito` -> `io.quarkus:quarkus-junit-mockito`
   - `io.quarkus:quarkus-junit5-component` -> `io.quarkus:quarkus-junit-component`
     (used by `channel-telegram`)
   - `io.quarkus:quarkus-junit5-internal` -> `io.quarkus:quarkus-junit-internal`
   - `org.apache.camel.quarkus:camel-quarkus-junit5` -> `org.apache.camel.quarkus:camel-quarkus-junit`
     (declared by both `app` and `channel-telegram`), with test imports moved
     from `org.apache.camel.test.junit5.*` to `org.apache.camel.test.junit6.*`.
     Renaming here pairs proactively with this checkpoint's JUnit 6 engine
     move; the old artifact still resolves on this checkpoint's CQ line but is
     removed upstream in CQ 3.34, making completion mandatory once Phase 3's
     member BOM brings CQ 3.38.
   Run the custom deployment-module tests before broad application tests.
8. Complete the JUnit 6 and test-stack moves:
   - bump `archunit.version` to `1.5.0` and switch the managed/test dependency
     from `archunit-junit5` to `archunit-junit6`;
   - rename the Testcontainers artifact (`postgresql` ->
     `testcontainers-postgresql`) and update `PostgresTestResource` imports to
     the new package locations;
   - align byte-buddy to the platform value and drop
     `-Dnet.bytebuddy.experimental=true` only after the suite passes without it;
   - align commons-lang3 to `3.20.0` (or drop the pin);
   - accept Mutiny `3.x` arriving through the platform: the full-reactor compile
     is the first gate for any removed API, followed by async/context tests;
   - spot-check `@CsvSource`/`@CsvFileSource` tests for FastCSV parsing changes.
9. Exercise PostgreSQL TLS 1.3, verify explicit Agroal pool limits (`max-size: 3` SQLite,
   `max-size: 10` Postgres), test Qute `TemplateException` handling (`e.getCause()`), REST security
   and transaction ordering, Testcontainers-based PostgreSQL tests, Flyway 12
   startup against both backends with checksum stability, and all native
   reflection/resource registrations.
10. Complete the Section 8 matrix and produce a separately deployable `3.33.3.1` artifact.
11. Soak the LTS artifact. Decide explicitly whether production stops at the LTS
    or accepts the regular-stream cadence needed for `3.38.3`.

### Phase 3: continue from 3.33.3.1 to 3.38.3

1. Proceed only after the 3.33 checkpoint is green and the latest-stream
   decision is recorded.
2. Generate a fresh updater analysis and dry-run patch for `3.38.3`; do not
   reuse the 3.27-to-3.38 proposal.
3. Review every official 3.34-3.38 migration guide and the target platform's
   extension recommendations.
4. Update JDBI to the catalog-recommended `1.10.1` (Jdbi `3.54.0`, built
   against Quarkus `3.38.0`) and run focused store tests for
   `ConstructorMapper`/`@BindMethods` record writes. Keep SQLite JDBC explicitly
   pinned to `3.0.11`: it is the latest published release, built against an
   older Quarkus baseline, so JVM and native proof is mandatory rather than a
   version choice.
5. Compile every LangChain4j-consuming module (`llm-providers`, `rag`,
   `persistence-*`, `self-evolve`, `channel-telegram`) against the member BOM's
   LangChain4j `1.17.2`; review upstream release notes for deprecations in tool
   execution, streaming, and content types before promoting.
6. Keep Apache Lucene pinned to `10.4.0` to preserve native image codec reflection
   reachability (`Lucene104Codec`).
7. Revalidate these post-3.33 behavior changes:

   - **Micrometer strict tag consistency**: the registry now throws on
     inconsistent tag key sets for the same metric name; audit every custom
     meter registration site and diff Prometheus output against the 3.33
     baseline (Micrometer itself moves `1.14.7` -> `1.17.0`);
   - **Quarkus REST `@Transactional` non-blocking dispatch**: JAX-RS endpoints enforce
     `@RunOnVirtualThread` while database transactions are confined to store DAOs;
   - **REST Assured 5.5.6 -> 6.0.1**: Java 17/Groovy 5 baselines and json-path
     reimplementation; verify fluent assertion compatibility across all REST suites;
   - **Jackson `2.19.2` -> `2.22.0`**: JSON round-trip tests over REST resources
     and LangChain4j guardrail payloads;
   - **Arc default interface method interception**: since 3.37 Arc applies
     method-level interceptor bindings on default interface methods; verify
     that any annotated default method receives its intended interception
     while unannotated default methods behave exactly as before;
   - **OpenTelemetry SDK `1.44.1` -> `1.62.0`**: no references to removed
     `LateBoundSpanProcessor`; diff exporter output (span attributes, semconv
     names emitted by the platform) against baseline; Amsha-owned GenAI/
     OpenInference attribute strings in `AgentOTelConstants` are unaffected by
     construction but must still be observed end-to-end;
   - **MCP Server `1.10.3` -> `1.13.1`**: Streamable HTTP lazy-initializes SSE by
     default since 1.13; smoke both HTTP and stdio transports against real MCP
     clients and confirm no transport hint or config key relied on by `amsha-mcp`
     changed meaning; optional new features (elicitation URL mode,
     `quarkus-mcp-server-oidc`) are out of scope for this migration;
   - **Integration-test host-header validation**: ensure runtime smoke tests validate reverse-proxy
     and localhost bindings; and
   - **Hibernate ORM 7.3/7.4 and Agroal `3.2.1`**: ensure augmentation and
     profile-specific persistence remain stable.

8. Regenerate the OpenAPI artifact and TS DTOs: SmallRye OpenAPI moves
   `4.0.12` -> `4.3.5`, so diff `target/openapi.yaml` against the previous
   checkpoint and rerun the `ts-dto` generation profile if the document changed.
9. Test actual executing threads wherever `@RunOnVirtualThread`, transactions,
   or the repository's event-loop guard interact. Never infer safety from the
   retained Vert.x context.
10. Build all three native persistence variants (SQLite, PostgreSQL, Filesystem) and run the
    repository's local runtime, platform-matrix (`verify_native_matrix.sh`), and ONNX provenance
    gates (`verify_onnx_release_provenance.sh`) against the exact artifacts intended for promotion.
    The SQLite native build is the decisive proof for the stale-baseline
    `quarkus-jdbc-sqlite` extension, including FTS5 and `vec0` loading.
11. Run a canary/soak with startup, authorization, persistence, approval, MCP,
    Telegram, metrics, traces, latency, memory, and shutdown observations.
12. Promote only if every acceptance criterion passes and the 3.33 artifact
    remains immediately deployable.

### Phase 4: keep future migrations small

After the migration, choose one maintenance policy:

- **LTS policy**: stay on the newest supported LTS patch, review patches at
  least monthly, and begin the next LTS migration before the current stream's
  support window closes; or
- **Latest-stream policy**: review every Quarkus minor and patch release and do
  not allow another multi-stream jump to accumulate.

The selected cadence must include a non-applying updater analysis, migration
guide review, dependency/BOM comparison, focused JVM tests, and native smoke.
Framework updates are not merged by a dependency bot without these gates.

## 7. Version-specific risk register

| Risk | Why it matters here | Required control |
| --- | --- | --- |
| Aggregator is invisible to updater | Root `update -N` found no extensions | Analyze from `app/pom.xml`; verify the nested dry run scans the full reactor |
| Platform member skew | Core, LangChain4j, Camel, and MCP currently have separate controls | Import target platform member BOMs at one version and inspect effective POMs |
| Root pins override the BOM | byte-buddy, jboss-logmanager, bouncycastle, commons-lang3 pins outrank imports; two are below even today's platform | Align every pin to the checkpoint's managed value or record written justification; verify via `dependency:tree` |
| Mutiny major bump inside the platform | Mutiny moves `2.9.5` -> `3.3.0`; every module compiles against it transitively | Full-reactor compile as first gate; async/workspace-context behavior tests; review 3.x release notes for removed APIs |
| Independent extension compatibility | JDBI and SQLite are outside selected member BOMs; SQLite extension's build baseline predates the target stream | Use registry/release metadata, explicit versions (`1.10.1` JDBI, `3.0.11` SQLite), focused JVM and native tests |
| LangChain4j `1.5.0` -> `1.17.2` | Twelve minor releases across providers used by six modules | Compile all LangChain4j-consuming modules; review upstream release notes for tool/streaming/content deprecations |
| MCP behavior change in 1.13 | Streamable HTTP lazy SSE init becomes default; protocol features advance | Smoke HTTP and stdio transports against real clients; confirm config keys used by `amsha-mcp` keep their meaning |
| Camel test artifact removal | `camel-quarkus-junit5` is removed in CQ 3.34 while the member BOM brings CQ `3.38.0` | Rename to `camel-quarkus-junit` and migrate test imports to `org.apache.camel.test.junit6.*` at Phase 2 |
| ArchUnit/JUnit 6 engine mismatch | `archunit-junit5` targets JUnit 5/Platform 1.x | Bump ArchUnit to `1.5.0` and switch to `archunit-junit6`; verify discovery counts unchanged |
| Testcontainers 2 renames | Artifact and package names change; PG tests use them directly | Rename to `testcontainers-postgresql`, update `PostgresTestResource` imports, run PostgreSQL tests |
| Flyway major bump | Platform moves Flyway `11.11.2` -> `12.0.0` under both backends | Prove startup migration, checksum stability, and schema-history compatibility on SQLite and PostgreSQL |
| Lucene codec reflection pinning | Native vector search relies on exact `Lucene104Codec` in `reflect-config.json` | Pin Lucene at `10.4.0` unless native reflection configs are updated in lockstep |
| Quarkus deployment APIs | Amsha owns a custom `deployment` module and build steps | Compile and test augmentation first at every checkpoint; preserve `MultiBuildItem` read-only usage |
| Jandex plugin/index skew | Seven modules pin `jandex-maven-plugin` `3.5.3` under a parent managing `3.6.0`, while the platform's Jandex runtime moves `3.4.0` -> `3.6.0` | Align the pins to the parent value at Phase 2 and re-prove index-dependent augmentation and native tests |
| Arc implementation coupling | Core references `io.quarkus.arc.Subclass` | Standardize proxy unwrapping in `ClassUtils` or defensive type hierarchy traversal |
| Agent argument composition | JaCoCo, Byte Buddy, module opens, and native access share `argLine` | Use late evaluation syntax `@{argLine}`; verify all agents compose in effective Surefire command |
| REST thread semantics | Safety invariants depend on the actual executing thread | Enforce `@RunOnVirtualThread` via `RestResourceBuildStep`; re-run event-loop guard tests |
| Micrometer tag schema validation | Registry throws on inconsistent tag key sets; Micrometer itself moves three minors (`1.14.7` -> `1.17.0`) | Audit all meter registrations to ensure identical tag keys across branches; diff Prometheus output vs baseline |
| OpenTelemetry version distance | SDK `1.44.1` -> `1.62.0`, instrumentation `2.10` -> `2.28`, semconv several alpha lines | Use standard `BatchSpanProcessor` in `PhoenixExporterConfig`; diff exporter output and attribute names end-to-end |
| OpenAPI document drift | SmallRye OpenAPI `4.0.12` -> `4.3.5` may alter generated document feeding TS DTOs | Diff `target/openapi.yaml` per checkpoint; rerun `ts-dto` profile when changed |
| Agroal pool explosion | Default pool max-size rose from 20 to 50 in 3.31; pool implementation crosses a major line (`2.8` -> `3.2.1`) | Retain explicit `max-size: 3` (SQLite) and `max-size: 10` (Postgres) in `application.yaml`; re-prove pool limits |
| Persistence parity | SQLite is default, with PostgreSQL and filesystem peers | Compile, test, package, and smoke every backend; do not change schema in this migration |
| Native reachability | Reflection configs, SkillsJar resources, SQLite vec, and ONNX are release-critical | Build all variants and run native runtime/release-evidence gates |
| LC4J chat-memory JSON in native | QLC codec mixins (`$ChatMessageMixin` etc.) are package-private; native strips them without reflection registration → type-less writes rejected on read | Register LC4J message types + QLC mixin classes in `CoreReflectionConfig`; guarded by `ChatMessageJsonRoundTripTest` |
| OpenRewrite overreach | Generated patch removes pins and rewrites build configuration | Review every deletion and manually restore intentional aliases/pins |
| Non-LTS maintenance | 3.38 is a regular stream | Accept an explicit rapid-update cadence or stop at 3.33 LTS |

## 7.5 Execution lessons (Issues 01–04, Phase 1 + first half of Phase 2)

Full detail in `.scratch/quarkus-upgrade/lessons-learnt.md` (gitignored).
These are the durable takeaways that apply to every future checkpoint:

| Lesson | Impact | Rule going forward |
| --- | --- | --- |
| Root pins outrank imported BOMs | Two pins (`jboss-logmanager`, `bouncycastle`) were live defects below platform values before any bump started | Align every pin to the checkpoint's BOM value or justify (§4 rule 5) |
| Updater patches need hunk-level triage | Alias rewrite, out-of-scope jandex/pin removals, TC2 version injection — all observed in real dry runs | Never apply raw; review every hunk against the effective POM |
| Run updater from `app/pom.xml` | Aggregator root reports zero extensions | Always `-f app/pom.xml -DplatformVersion=X.Y.Z.W` |
| mise strips exported env vars | Smoke runner silently fell back to `.env` while child held a different key → deterministic 401 | Deliver keys via `.env` only; never trust shell exports through mise |
| Native smoke children have console logging off | Failures look silent without file-log injection | Set `QUARKUS_LOG_FILE_ENABLE=true` + `QUARKUS_LOG_FILE_PATH` for any debug run |
| QLC codec mixins stripped by native DCE | Chat-memory JSON written type-less, rejected on read (InvalidTypeIdException) | Register LC4J message types + mixin classes in `CoreReflectionConfig`; guarded by `ChatMessageJsonRoundTripTest` |
| TC2 `PostgreSQLContainer` is non-generic | Compile break on migration to TC2 | Drop `<?>` and diamond at adoption time |
| Camel test artifact removed in CQ 3.34 | `camel-quarkus-junit5` dead once member BOM brings CQ ≥ 3.34 | Rename to `camel-quarkus-junit`; migrate imports to `junit6.*` |
| ArchUnit needs `archunit-junit6` module | `archunit-junit5` targets JUnit Platform 1.x | Switch artifact at JUnit-6 checkpoints |
| Orphaned temp daemons squat ephemeral ports | Zonky embedded-PG hang class (blocked in `verifyReady`) | `lsof -nP -iTCP:<port>` before assuming a code bug; sweep `/tmp/amsha-*` orphans |
| Moderne CLI license-gated for private repos | Source-available testing recipes unusable without a license | Use direct Maven rewrite plugin + Central jars instead |
| Soak tooling required | Smokes prove instantiation, not sustainability | `scripts/dev/local_soak.py` watches health/auth/log-delta/RSS/deep probes over duration |

## 8. Verification matrix

Every command runs from the repository root unless noted. Commands requiring
credentials use the existing secret-safe workflow and must not print provider
responses, prompts, completions, keys, or authorization headers.

### 8.1 At every code checkpoint

If Java was changed:

```bash
python3 ./scripts/fix_fqns.py
./mvnw spotless:apply -q
```

Run PMD over the application and custom deployment dependency graph:

```bash
./mvnw -pl app,deployment -am pmd:check -DskipTests
```

Compile and install after dependency changes:

```bash
./mvnw clean install -DskipTests
```

Run the complete JVM test build and backend builds:

```bash
mise run build:test
mise run build:fs
mise run build:pg
```

Before the human creates a checkpoint commit:

```bash
mise run sonar:issues
```

### 8.2 Dependency and extension gates

For each target, retain and compare:

```bash
./mvnw help:effective-pom -Doutput=target/quarkus-effective-pom.xml
./mvnw -pl app -am dependency:tree -Dverbose
./mvnw -f app/pom.xml \
  io.quarkus.platform:quarkus-maven-plugin:3.38.3:info
```

The gate fails on an unexpected second release family for Arc, Vert.x, Netty,
Jackson, JUnit, Testcontainers, OpenTelemetry, Graal SDK, or Jakarta APIs; an
unmanaged extension; or a platform recommendation that has not been resolved
or explicitly justified.

### 8.3 Focused behavioral gates

At minimum, run focused suites covering:

- `deployment` augmentation, generated resources, custom scope registration,
  unremovable beans, and reflection build items;
- Quarkus test discovery and the pre/post-migration test count;
- REST resources, REST Assured integration tests, authentication, workspace
  ownership, internal admin mutations, and HITL approval callbacks;
- actual-thread event-loop guards and `@RunOnVirtualThread` handlers;
- transaction boundaries and every persistence backend;
- Qute prompt/template rendering and exception behavior;
- WebSockets Next and Telegram/Camel routing;
- MCP HTTP and stdio transports;
- OpenTelemetry spans/export, Micrometer/Prometheus registration, and health;
  and
- startup registration of `@Startup` beans and native discovery of
  `@Unremovable` optional implementations.

Run the master REST validator against the checkpoint runtime with the
appropriate `TEST_BASE`. Any child-suite failure must propagate a nonzero exit
code.

### 8.4 Native and release gates

Build all native persistence variants:

```bash
mise run build:native-local-all
```

Run the reusable isolated native smoke against the exact SQLite artifact:

```bash
mise run smoke:local-runtime -- native --binary dist/amsha-sqlite
```

For release evidence, also run:

```bash
bash scripts/verify_native_matrix.sh
bash scripts/verify_onnx_release_provenance.sh
```

Equivalent PostgreSQL and filesystem artifact checks are required before those
variants are promoted. A local smoke does not substitute for the supported
platform matrix, golden-corpus/leak evidence, or signed SBOM/provenance gates.

### 8.5 Documentation and diff hygiene

After documentation changes:

```bash
python3 ./scripts/verify_docs.py
git diff --check
```

Review generated files, application configuration, `.env.example`, native
profiles, container builder images, and all version-bearing documentation.
Never stage, unstage, or commit on behalf of the human reviewer.

## 9. Acceptance and stop conditions

A checkpoint is accepted only when:

1. the selected Quarkus and member BOM versions are documented and coherent;
2. every used third-party extension is managed or explicitly versioned with
   compatibility evidence;
3. every intervening official migration guide has been reviewed and each
   relevant item has a test or written disposition;
4. the custom deployment module compiles, augments the app, and passes its
   tests;
5. test discovery counts have not silently decreased;
6. all required formatting, PMD, JVM, persistence, REST, native, security,
   metrics, telemetry, MCP, and release-evidence gates pass;
7. the exact artifact has completed the agreed canary/soak period; and
8. the previous checkpoint artifact and configuration remain deployable.

Stop the migration at the current checkpoint when:

- the registry reports an extension with no compatible target and a focused
  compatibility spike cannot close the gap;
- an internal Quarkus API change requires a redesign rather than a bounded
  adaptation;
- a native-only reflection, resource, library-load, or shutdown regression is
  unresolved;
- transaction, authorization, approval, or event-loop safety semantics differ;
- persistence compatibility would require destructive or non-reversible data
  changes; or
- the latest-stream maintenance commitment is not accepted.

Stopping at a green `3.33.3.1` LTS checkpoint is a valid decision, not a failed
migration.

## 10. Rollback

Rollback selects the previously validated source revision, artifact, and
configuration. It does not attempt to run OpenRewrite in reverse.

Framework migration checkpoints must not include an irreversible Flyway or data
migration. If a schema change later proves unavoidable, it requires a separate
expand/contract design compatible with both old and new application artifacts,
a verified backup, and a proven old-artifact startup path.

Retain for every checkpoint:

- source revision and human-reviewed updater patch summary;
- JDK, Maven, GraalVM/Mandrel, platform, member BOM, and extension versions;
- effective POM and dependency trees;
- JVM and native artifact digests;
- non-secret configuration/schema identity and Flyway history;
- PMD, test, REST, native, platform-matrix, provenance, and soak results; and
- startup, error, latency, memory, metric, and trace baselines.

Rollback triggers include augmentation/startup failure, missing reflection or
resources, persistence incompatibility, authorization or transaction behavior
changes, event-loop blocking, metric-registration exceptions, telemetry loss,
protocol incompatibility, and material latency or memory regression.

For database-compatible checkpoints, rollback is deployment of the previous
artifact/configuration followed by health, persistence, authorization, and
smoke verification. External protocol changes require an explicit compatibility
window and coordinated consumer rollback.

## 11. Considered options

### Direct manual version bump

Rejected. It would miss Quarkus extension-catalog recommendations, official
version-boundary recipes, JUnit/build configuration changes, and Camel-specific
recipes. It offers the least evidence for a reactor of this size.

### One direct 3.27.2 to 3.38.3 rewrite and release

Rejected as a release strategy. The updater can compose recipes across the
entire range, but one production jump makes failures harder to localize and
removes the supported 3.33 LTS rollback boundary. A direct dry run remains
useful as planning evidence.

### Stop permanently on 3.33.3.1 LTS

Retained as the lower-risk production alternative. It is preferred when Amsha
does not need a 3.34-3.38 capability and the team values a longer maintenance
window over the newest features.

### Permanently add raw OpenRewrite to the build

Rejected for this migration. It duplicates part of the Quarkus updater while
losing catalog orchestration. Reconsider only for repeated, repository-specific
rewrites that justify their own tested recipe artifact.

### Let a dependency bot merge framework updates

Rejected. Bots may open version proposals, but Quarkus updates require
migration-guide review, coherent member BOMs, custom augmentation tests, and
native/runtime evidence before a human-controlled merge.

## 12. Consequences

Positive consequences:

- official Quarkus recipes and registry recommendations reduce mechanical
  migration work;
- LTS checkpoints make failures easier to attribute and rollback;
- platform member BOMs reduce Camel and MCP version skew;
- the process explicitly protects Amsha's native-image and multi-backend
  invariants; and
- a recurring maintenance cadence prevents another large version gap.

Costs and trade-offs:

- the migration takes at least two cross-stream validation cycles instead of
  one version edit;
- native builds, backend validation, and soak periods dominate elapsed time;
- some updater output must be corrected manually;
- independently versioned JDBI and SQLite extensions remain compatibility
  responsibilities; and
- choosing 3.38.3 creates an ongoing regular-stream update obligation that an
  LTS-only policy avoids.

## 13. Primary sources

- [Quarkus 3.38.3 release](https://quarkus.io/blog/quarkus-3-38-3-released/)
- [Quarkus update guide](https://quarkus.io/guides/update-quarkus)
- [Quarkus migration guides](https://github.com/quarkusio/quarkus/wiki/Migration-Guides)
- [Quarkus community security support](https://quarkus.io/security/)
- [Quarkus LTS policy](https://quarkus.io/blog/lts-releases/)
- [Quarkus update recipes](https://github.com/quarkusio/quarkus-updates)
- [quarkusio/skills (`quarkus-update` agent skill)](https://github.com/quarkusio/skills)
- [code-with-quarkus-compare reference projects](https://github.com/quarkusio/code-with-quarkus-compare)
- [Quarkus 3.38.3 Maven updater source](https://github.com/quarkusio/quarkus/blob/3.38.3/devtools/maven/src/main/java/io/quarkus/maven/UpdateMojo.java)
- [Quarkus 3.38.3 updater orchestration](https://github.com/quarkusio/quarkus/blob/3.38.3/independent-projects/tools/devtools-common/src/main/java/io/quarkus/devtools/commands/handlers/UpdateProjectCommandHandler.java)
- [OpenRewrite recipe runner](https://docs.openrewrite.org/running-recipes/getting-started)
- [Camel Quarkus 3.38.0 release](https://github.com/apache/camel-quarkus/releases/tag/3.38.0)
- [Camel Quarkus 3.31 migration guide (junit5 rename; removal in 3.34)](https://camel.apache.org/camel-quarkus/next/migration-guide/3.31.0.html)
- [Quarkiverse MCP Server Extension](https://github.com/quarkiverse/quarkus-mcp-server)
- [`quarkus-mcp-server-bom` 3.38.3 POM (pins MCP Server `1.13.1`)](https://repo1.maven.org/maven2/io/quarkus/platform/quarkus-mcp-server-bom/3.38.3/quarkus-mcp-server-bom-3.38.3.pom)
- [quarkus-mcp-server 1.13.0 release notes (behavior carried into the pinned 1.13.1 patch)](https://github.com/quarkiverse/quarkus-mcp-server/releases/tag/1.13.0)
- [`io.quarkus:quarkus-bom` 3.27.2 POM](https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.27.2/quarkus-bom-3.27.2.pom)
- [`io.quarkus:quarkus-bom` 3.38.3 POM](https://repo1.maven.org/maven2/io/quarkus/quarkus-bom/3.38.3/quarkus-bom-3.38.3.pom)
- [Quarkus 3.31 migration guide (JUnit relocations, Testcontainers 2)](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31)
- [JUnit 6 upgrade notes](https://github.com/junit-team/junit-framework/wiki/Upgrading-to-JUnit-6.0/How-tos)
- [ArchUnit JUnit 6 support (#1556) and `archunit-junit6` in 1.5.0](https://github.com/TNG/ArchUnit/issues/1556)
- [REST Assured 6.0.0 release notes](https://github.com/rest-assured/rest-assured/wiki/ReleaseNotes60)
- [Byte Buddy compatibility table (Java 25 since 1.17.0)](https://github.com/raphw/byte-buddy)
- [Mutiny release history (3.x line)](https://github.com/smallrye/smallrye-mutiny/releases)
- [LangChain4j `ChatModel` javadoc (`chat(ChatRequest)` main API)](https://docs.langchain4j.dev/apidocs/dev/langchain4j/model/chat/ChatModel.html)
- [Flyway engine release notes (12.x breaking changes)](https://documentation.red-gate.com/flyway/release-notes-and-older-versions/release-notes-for-flyway-engine)
- [Testcontainers `testcontainers-postgresql` 2.0 artifact](https://mvnrepository.com/artifact/org.testcontainers/testcontainers-postgresql/2.0.0)
- [Quarkiverse JDBI Extension](https://github.com/quarkiverse/quarkus-jdbi)
- [Quarkiverse SQLite JDBC Extension](https://github.com/quarkiverse/quarkus-jdbc-sqlite)
- [DJL HuggingFace Tokenizers](https://github.com/deepjavalibrary/djl/tree/master/extensions/tokenizers)
- [ONNX Runtime Java API](https://onnxruntime.ai/docs/api/java/)
- [Apache Lucene 10 Releases](https://lucene.apache.org/core/)
- [Bouncy Castle Crypto Packages](https://www.bouncycastle.org/)
- [Bucket4j Rate Limiting](https://bucket4j.com/)

The supporting research note contains the full 3.28-3.38 migration-guide matrix
and the complete primary-source index.
