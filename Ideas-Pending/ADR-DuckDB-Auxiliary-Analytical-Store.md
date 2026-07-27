# ADR: DuckDB as an Auxiliary Analytical Store

- Status: Proposed
- Date: 2026-07-23
- Decision owners: Amsha Agent maintainers
- Related invariants: `docs/Architecture_Invariants.md` persistence parity, transaction, backend-selection, and native-image rules

## Context

Amsha uses SQLite as its default zero-configuration operational database, with
PostgreSQL and filesystem adapters available for supported persistence ports.
Those stores hold authoritative application state such as identities, sessions,
approvals, credentials, configuration, goals, checkpoints, cron definitions,
memory records, and audit events.

SQLite is a good default for that workload. It is embedded, reliable, easy to
distribute, and efficient for small indexed reads and transactional writes.
PostgreSQL remains the appropriate operational option for deployments needing a
database server and greater write concurrency.

Some emerging workloads are different:

- aggregating token use and estimated cost over long periods;
- calculating latency distributions and tool/model success rates;
- exploring large audit and execution histories;
- comparing evaluations, prompts, agents, models, and providers;
- scanning or joining exported Parquet, CSV, or JSON datasets;
- powering local dashboards and producing offline reports;
- preparing bounded analytical datasets for researchers without loading the
  operational database.

These are column-oriented analytical workloads. Running them directly against
the operational SQLite database can cause long scans, increase lock duration,
pollute the page cache, and couple dashboard availability to the application's
critical state store. Maintaining many precomputed operational tables solely for
ad hoc analysis would also complicate migrations and write paths.

DuckDB is an embedded analytical database with a vectorized, column-oriented
execution engine and strong support for querying Parquet and other data files.
It is available to Java through JDBC and can be connected to Quarkus through a
compatible Quarkiverse JDBC extension when one is supported for the selected
versions, or through explicitly configured JDBC/Agroal integration. JDBI can be
used above that JDBC datasource where it adds value.

DuckDB is not equivalent to SQLite despite both being embedded, in-process
databases stored in local files. SQLite is optimized primarily for transactional
application access. DuckDB is optimized primarily for bulk ingestion, scans,
joins, and aggregation. Treating them as interchangeable would ignore their
different concurrency, indexing, extension, deployment, and operational
characteristics.

## Decision

Adopt DuckDB, when implemented, as an **optional auxiliary analytical store**.
It will complement the selected operational persistence backend; it will not be
a new implementation of Amsha's operational persistence ports.

The authoritative data flow is:

```text
Operational backend (SQLite, PostgreSQL, or filesystem)
  -> committed analytical events or bounded snapshot/export
  -> asynchronous, restart-safe ingestion
  -> DuckDB analytical tables and/or Parquet datasets
  -> reports, dashboards, evaluation analysis, and local exploration
```

The initial implementation must satisfy these boundaries:

1. `AMSHA_PERSISTENCE_BACKEND=duckdb` is not introduced.
2. No existing operational store port receives a DuckDB implementation.
3. DuckDB unavailability, corruption, migration failure, or ingestion lag must
   not prevent normal agent execution or operational writes.
4. DuckDB work must never execute inside an operational `@Transactional`
   boundary.
5. Analytical consumers are read-only with respect to authoritative state.
6. The operational database remains the source of truth. DuckDB can be deleted
   and rebuilt without losing required application state.
7. DuckDB is disabled by default until its packaging, resource use, and native
   behavior have passed the gates in this ADR.

This decision deliberately avoids triggering the repository invariant that
every operational store port must have SQLite, PostgreSQL, and filesystem
implementations. A narrowly defined analytical API may be introduced, but it
must not masquerade as, replace, or become a dependency of an operational store
port.

## Goals

- Accelerate scans, joins, grouping, percentiles, and other analytical queries.
- Keep expensive analytical work away from operational persistence.
- Query Parquet and other supported datasets without importing all data into
  SQLite first.
- Support reproducible local reporting and evaluation workflows.
- Preserve Amsha's zero-configuration SQLite default.
- Make analytical storage disposable and rebuildable.
- Provide bounded resource, retention, privacy, and failure-isolation controls.
- Establish an evidence-based gate before attempting GraalVM native packaging.

## Non-goals

- Replacing SQLite as the default operational database.
- Adding DuckDB as a selectable implementation of every persistence port.
- Using DuckDB for identities, sessions, approvals, credentials, invitations,
  cron state, checkpoints, goals, or other latency-sensitive mutable state.
- Providing a shared multi-node analytical service.
- Guaranteeing immediate read-after-write consistency in analytical views.
- Allowing dashboards to issue arbitrary SQL against operational databases.
- Copying secrets, raw credentials, prompt-private data, or unrestricted tool
  payloads into analytical tables.
- Making application correctness depend on successful analytical ingestion.

## Why DuckDB

DuckDB's primary advantage is efficient local analytics:

- columnar execution reads only columns needed by a query;
- vectorized operators make bulk filtering, joins, and aggregation efficient;
- analytical SQL includes window functions, statistical aggregates, and rich
  nested-data handling;
- Parquet predicate and column pushdown can avoid unnecessary reads;
- a server process is not required for single-node use;
- an analytical database or exported Parquet dataset is easy to inspect with
  external data tools.

These advantages are material for questions such as:

```sql
-- Illustrative only; the final schema and privacy policy remain design work.
SELECT
    model_id,
    date_trunc('day', completed_at) AS day,
    count(*) AS requests,
    approx_quantile(latency_ms, 0.95) AS p95_latency_ms,
    sum(input_tokens + output_tokens) AS total_tokens
FROM analytical_model_calls
WHERE completed_at >= current_date - INTERVAL 30 DAY
GROUP BY model_id, day;
```

DuckDB does not create the same advantage for a point lookup of one session, an
approval update, or a concurrent membership mutation. Those operations remain
with SQLite or PostgreSQL.

## Architecture

### 1. Module boundary

Create a dedicated module only when implementation begins, tentatively named
`amsha-analytics-duckdb`. It may depend on a small analytics API and shared safe
event DTOs. It must not depend on `amsha-app`, and operational modules must not
depend on it.

As a new CDI library module it requires Jandex, `META-INF/beans.xml`,
application indexing, test configuration, native reflection review, and a
startup-log verification in accordance with repository invariants.

The module owns:

- DuckDB datasource and connection configuration;
- analytical schema and schema-version management;
- ingestion checkpoints and idempotency;
- analytical projections and queries;
- retention, compaction, rebuild, and health reporting;
- DuckDB-specific SQL and compatibility tests.

It does not own operational event production or authorization decisions.

### 2. Separate named datasource

DuckDB and the operational database must use separate datasource identities.
The existing unqualified `AgroalDataSource` and JDBI producers remain dedicated
to the selected operational backend. DuckDB receives an explicitly named
datasource and a qualified analytical JDBI instance if JDBI is retained.

Illustrative configuration names are:

```yaml
amsha:
  analytics:
    duckdb:
      enabled: ${AMSHA_ANALYTICS_DUCKDB_ENABLED:false}
      path: ${AMSHA_ANALYTICS_DUCKDB_PATH:./amsha-data/analytics.duckdb}
      ingest-interval: ${AMSHA_ANALYTICS_DUCKDB_INGEST_INTERVAL:PT30S}
      retention: ${AMSHA_ANALYTICS_DUCKDB_RETENTION:P90D}
```

The final implementation must also add every `@ConfigProperty` to
`application.yaml`, `.env.example`, and the owning module README. Paths must be
resolved through the application's established data/workspace path controls;
callers must not supply arbitrary filesystem paths.

Connection pooling must be conservative. DuckDB is embedded and its concurrency
model does not benefit from a conventional large OLTP pool. The proof of concept
must determine whether a single writer connection plus bounded read connections,
or serialized access through an analytics executor, is the safest supported
configuration for the selected JDBC driver version.

### 3. Ingestion contract

Analytical ingestion is asynchronous and at-least-once. It must be idempotent.
Each exported record has a stable event or projection identifier and a schema
version. DuckDB enforces or simulates deduplication so replay cannot double
counts.

Two ingestion sources are permitted:

1. **Committed analytical events.** Operational code records a safe, bounded
   event as part of its normal committed work or through an existing durable
   event/outbox mechanism. A background job later copies it to DuckDB.
2. **Bounded snapshots/exports.** A background job reads committed operational
   records in stable pages, exports a versioned projection, then advances its
   independent checkpoint.

The implementation must not add a network call, DuckDB call, file export, or
external tool execution to an operational transaction. If an outbox is needed,
only the small durable outbox write belongs in the operational transaction.

Each ingestion checkpoint contains at least:

- source type and source instance identity;
- last durable cursor or high-water mark;
- projection schema version;
- last attempted and last successful timestamps;
- bounded error status and retry metadata.

Advancing a checkpoint and committing the corresponding DuckDB batch must be
atomic from the analytical store's perspective. A crash before advancement
causes safe replay. Lag is observable and does not block operational traffic.

### 4. Consistency and rebuild

Analytical data is eventually consistent. APIs and UI surfaces must label the
last successful refresh time and must not present analytical totals as strongly
consistent operational state.

Rebuild is a first-class operation:

1. stop or pause analytical ingestion;
2. create a new database or dataset in a validated temporary location;
3. replay the selected source range;
4. validate row counts, schema version, deduplication, and required aggregates;
5. atomically activate the rebuilt artifact where the platform permits;
6. retain or clean up the previous artifact according to a bounded recovery
   policy.

Rebuild must not mutate or lock the operational source for an unbounded period.
Snapshot reads use stable pagination and configured batch/rate limits.

### 5. Initial analytical model

The first schema should be intentionally narrow. Candidate facts are:

- model-call completion with provider/model identifiers, bounded timing, token
  counts, outcome category, and estimated cost inputs;
- tool-call completion with capability identifier, bounded timing, approval
  disposition, and outcome category;
- agent-turn completion with persona/role identifier, bounded timing, outcome,
  and aggregate counts;
- evaluation result with suite/case/model identifiers, score, duration, and
  version metadata;
- safe audit-event projection with category, action, result, actor pseudonymous
  identifier, workspace pseudonymous identifier, and timestamp.

Dimensions may include versioned model, provider, persona, tool capability,
evaluation suite, and calendar attributes.

The schema must not duplicate operational records wholesale. It should store the
minimum projection required for approved analytical questions. Free-form prompt
and response bodies are excluded from the initial schema.

### 6. Privacy, security, and authorization

Analytics is not a bypass around workspace authorization or data minimization.

- Raw API keys, session tokens, CSRF values, OAuth/OIDC tokens, authorization
  codes, invitation tokens, key digests, vault references, and secret values are
  forbidden.
- Prompt bodies, model responses, tool arguments/results, file contents, and
  unrestricted audit metadata are excluded by default.
- User and workspace identifiers are pseudonymized where aggregate analysis
  does not require stable operational identifiers.
- Any approved drill-down retaining a stable identifier documents its purpose,
  retention, and authorization boundary.
- Query services apply the same user/workspace authorization policy as the
  source data. Possession of a local analytics file is not treated as
  authorization.
- Logs contain bounded error summaries using safe root messages and never SQL
  parameter values containing private data.

The DuckDB file and any Parquet exports inherit the application's local data
permissions and backup policy. Documentation must make clear that exporting an
analytical file can export sensitive metadata even when secrets are absent.

### 7. Query surface

Application code uses a typed `AnalyticsQueryPort` or purpose-specific query
interfaces. It does not expose arbitrary SQL through REST, channels, agents, or
tools.

Initial query methods should correspond to reviewed product needs, for example:

- usage and cost by time range, workspace, provider, and model;
- latency and error distribution by model or tool;
- evaluation comparison by suite and candidate;
- ingestion status and freshness.

Queries require bounded time ranges, result limits, cancellation/timeouts, and
resource guards. Large exports are explicit artifact jobs rather than chat
responses. Generated HTML follows the existing HTML artifact transformer and
browser preview path.

Direct SQL access may be documented for an offline administrator workflow, but
it is outside the application API and carries an explicit data-access warning.

### 8. Resource isolation

Embedded analytics competes with the agent runtime for CPU, memory, file
descriptors, and disk bandwidth. Defaults must favor agent execution.

The implementation defines and tests:

- maximum ingestion batch size and frequency;
- maximum analytical query concurrency;
- query timeout/cancellation behavior;
- DuckDB memory and temporary-directory limits where supported;
- retention and disk-space thresholds;
- low-disk behavior that pauses ingestion before operational storage is
  endangered;
- shutdown ordering that safely closes writers and checkpoints;
- startup recovery after an interrupted batch or rebuild.

An analytics circuit breaker disables ingestion and queries after repeated
storage failures while leaving operational health intact. Analytics health is a
separate readiness/detail signal and is not part of the core liveness gate.

### 9. Schema evolution

DuckDB analytical migrations are owned by `amsha-analytics-duckdb` and are not
added to the operational SQLite/PostgreSQL Flyway version sequence. Their
namespace, location, and schema-history mechanism must be independent.

Before selecting Flyway, verify that the repository's chosen Flyway and DuckDB
versions support the needed operations. If they do not, use a small,
transactional, module-owned schema-version runner rather than pretending DuckDB
is SQLite or registering an incorrect dialect.

Every projection includes an explicit schema version. Breaking projection
changes either provide an idempotent migration or require a documented rebuild.
Because the store is disposable, rebuild is preferred when it is safer and
operationally bounded.

### 10. JVM and native-image support

JVM mode is the first delivery target. The DuckDB JDBC driver includes native
components, so version compatibility, platform artifacts, temporary extraction,
class initialization, and packaging must be verified on every supported
operating system and architecture.

Native-image support is not assumed. A proof of concept must demonstrate:

- driver loading and connection creation in the native binary;
- correct bundling/loading of the DuckDB native library;
- reflection/resource registration through the owning module's central
  reflection configuration;
- build-time versus run-time class initialization choices;
- database creation, ingestion, query, restart, and shutdown;
- packaging on all supported native targets;
- no unexpected executable extraction or writable-path requirement.

If those checks fail, the feature remains JVM-only and the native build excludes
the DuckDB module and beans. Native binaries must continue to operate normally
with analytics disabled. The implementation must not silently fall back to
another analytical engine while claiming DuckDB is active.

## Operational behavior

### Enablement

DuckDB analytics is opt-in. Enabling it creates only analytical storage and does
not change `AMSHA_PERSISTENCE_BACKEND`. Startup logs report:

- whether DuckDB analytics is enabled;
- resolved safe database location without credentials;
- schema version;
- last successful ingestion time and current lag;
- whether the runtime is JVM or an explicitly verified native build.

### Failure modes

| Failure | Required behavior |
| --- | --- |
| DuckDB file absent | Create or rebuild it when enabled |
| DuckDB unavailable at startup | Disable analytics, report degraded analytics health, continue operational startup |
| Schema incompatible | Do not query it; require migration/rebuild; continue operational startup |
| Batch fails | Roll back batch, retain checkpoint, retry with bounded backoff |
| Duplicate delivery | Deduplicate by stable identifier |
| Low disk | Pause ingestion and exports before threatening operational storage |
| Query exceeds limits | Cancel/fail the query without affecting operational work |
| Database corrupt | Quarantine safely where possible and rebuild from authoritative sources |
| Native driver unsupported | Keep feature unavailable in native mode with a clear startup/configuration error |

### Backup and recovery

DuckDB is derived data and is not required in the primary operational backup.
Backing it up may reduce rebuild time, but restoration validates source identity,
projection version, and checkpoint compatibility before activation.

Parquet exports have explicit retention and manifest metadata. Partial exports
are written to a temporary name and published only after validation. Cleanup
never follows untrusted paths or broad globs.

## Alternatives considered

### Replace SQLite with DuckDB

Rejected. It would optimize the wrong workload, require implementations for all
operational ports, expand migration parity, and introduce unnecessary
concurrency and native-packaging risk for critical mutable state.

### Add `AMSHA_PERSISTENCE_BACKEND=duckdb`

Rejected for the initial design. Backend selection means authoritative storage
in Amsha; DuckDB is deliberately derived and rebuildable. Reusing that switch
would misrepresent its role and invite operational-port coupling.

### Run analytical SQL directly on SQLite

Acceptable for small installations and simple bounded reports, but insufficient
as the long-term design for large scans, percentiles, broad joins, and Parquet
analysis. It also fails the desired workload isolation.

### Use PostgreSQL for both operational and analytical queries

Reasonable for server deployments with careful replicas, indexes, materialized
views, and resource governance. It does not satisfy the embedded,
zero-administration local analytics goal and does not help default SQLite
deployments. DuckDB remains optional rather than mandatory for PostgreSQL users.

### Export Parquet without maintaining DuckDB

Viable and complementary. Parquet may become the durable analytical interchange
format while DuckDB provides the local query engine. For small scope, a
Parquet-only export phase can precede the persistent DuckDB catalog.

### Introduce a remote analytical service

Deferred. ClickHouse, a warehouse, or a managed analytics platform may suit
large multi-node deployments, but adds network I/O, credentials, administration,
and data-governance scope. A future analytics API should allow such an adapter
without changing operational ports.

## Delivery plan

### Phase 0: Proof of concept and measurements

1. Confirm a DuckDB JDBC/Quarkus combination compatible with the repository's
   JDK, Quarkus, Agroal, JDBI, and target platforms.
2. Create an isolated benchmark outside operational request paths using
   representative, sanitized event shapes and dataset sizes.
3. Compare SQLite and DuckDB for bounded point queries, bulk ingestion,
   time-window aggregation, percentiles, joins, and Parquet scans.
4. Measure startup, memory, disk, ingestion throughput, query latency, and
   interference with concurrent agent workloads.
5. Test single-writer behavior, connection pooling assumptions, crash recovery,
   file locking, and multi-process access.
6. Attempt the native-image gate without making native support a release
   dependency.

Phase 0 produces a written result and dependency/security review. It adds no
production data flow.

### Phase 1: Safe event contract and Parquet export

1. Define versioned, privacy-reviewed analytical event DTOs.
2. Reuse or introduce a durable committed-event/outbox boundary.
3. Implement idempotent paged export with checkpoints and manifests.
4. Provide bounded Parquet export and validation.
5. Add retention, disk thresholds, metrics, and rebuild tests.

This phase can deliver useful offline analysis before embedding DuckDB in the
application.

### Phase 2: Optional JVM DuckDB module

1. Add the dedicated CDI module, named datasource, driver, schema manager, and
   qualified query components.
2. Ingest the approved projections asynchronously.
3. Add typed usage, latency, outcome, and evaluation queries.
4. Expose freshness and separate analytical health.
5. Add failure-isolation, restart, deduplication, privacy, and resource-limit
   integration tests.
6. Document JVM enablement, storage, rebuild, retention, and data sensitivity.

### Phase 3: Product surfaces

1. Add authorized admin reporting APIs and UI views backed by typed queries.
2. Display freshness and incomplete-range warnings.
3. Make large report/export generation an artifact job.
4. Add query budgets, cancellation, audit records, and cross-workspace denial
   tests.

### Phase 4: Native support, only if proven

Enable DuckDB analytics in native distributions only after every native gate
passes on supported targets. Otherwise document it as JVM-only and keep native
artifacts free of the driver and its native library.

## Acceptance criteria

- Enabling DuckDB does not change or bypass the selected operational backend.
- Disabling, deleting, corrupting, or exhausting the DuckDB store does not
  prevent operational startup, agent execution, or authoritative writes.
- No DuckDB query, ingestion, export, or driver call occurs inside an
  operational `@Transactional` method.
- Ingestion is asynchronous, idempotent, restart-safe, and exposes measurable
  freshness/lag.
- Replaying the same source range produces the same analytical counts without
  duplicates.
- The entire analytical store can be rebuilt from authoritative committed
  sources or versioned exports.
- A separate named datasource and qualifier prevent accidental injection of
  DuckDB into operational adapters.
- Default connection and resource limits do not materially degrade a concurrent
  representative agent workload according to Phase 0 thresholds.
- Analytical schemas contain none of the forbidden secrets or raw private
  payloads, and cross-workspace authorization tests pass.
- Queries are typed and bounded; no arbitrary SQL endpoint or agent tool is
  introduced.
- Low disk, failed batch, incompatible schema, query timeout, interrupted
  rebuild, and corrupt-database scenarios fail safely.
- JVM packaging passes on all supported platforms before release.
- Native support is advertised only after native driver loading, packaging,
  reflection, resource, restart, and functional tests pass on supported native
  targets.
- All new configuration appears in `application.yaml`, `.env.example`, and the
  module README, and all required documentation and focused build checks pass.

## Consequences

Amsha gains an analytical engine suited to long scans, aggregations, evaluation
comparison, and open data formats without weakening SQLite's role as the
zero-configuration operational default. Analytical failures and resource spikes
are isolated from critical state transitions, and derived data remains
disposable.

The cost is another embedded native dependency, an additional schema lifecycle,
event/export contracts, disk usage, privacy review, and operational controls.
Eventual consistency must be visible to users. Native-image support may remain
unavailable. These costs are justified only if Phase 0 measurements demonstrate
a meaningful advantage for real Amsha workloads; otherwise Parquet export or
bounded queries against the existing backend remain the simpler solution.

## Decision trigger

This ADR remains `Proposed` until Phase 0 records:

- at least two concrete product queries whose representative performance or
  isolation requirement is not met safely by the operational backend;
- measured DuckDB benefit and acceptable runtime interference;
- a supported JVM dependency combination and licensing/security review;
- an agreed privacy-reviewed initial projection schema;
- a named owner for retention, rebuild, and compatibility maintenance.

Only then should implementation begin. Native support is a separate later
decision and is not implied by accepting the JVM analytical design.
