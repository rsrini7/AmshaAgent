=== GAP 1: BLOCKING FOR POSTGRES — columnDefinition Hardcodes in Core Entities ===
Files:
  arcclaw-core/.../state/AgentCheckpoint.java
  arcclaw-core/.../state/ReasoningStepEntity.java
  arcclaw-core/.../state/AgentCronEntity.java

These JPA entities live in arcclaw-core but contain SQLite-specific columnDefinition strings:

  // ReasoningStepEntity
  @Column(columnDefinition = "INTEGER")
  private Long id;

  // AgentCheckpoint
  @Column(columnDefinition = "INTEGER")
  private Long checkpointid;

  // AgentCronEntity — most dangerous
  @Column(columnDefinition = "INTEGER NOT NULL DEFAULT 0")
  private boolean runOnce;

On PostgreSQL, these emit malformed DDL:
  - INTEGER PRIMARY KEY for Long fields should be BIGSERIAL or GENERATED ALWAYS AS IDENTITY
  - INTEGER NOT NULL DEFAULT 0 for a boolean field must be BOOLEAN NOT NULL DEFAULT FALSE

Action:
1. Remove ALL columnDefinition = "..." strings from these three entities.
2. For the IDENTITY/autoincrement columns (AgentCheckpoint.checkpointid, ReasoningStepEntity.id),
   rely solely on @GeneratedValue(strategy = GenerationType.IDENTITY) — Hibernate and
   ArcClawSQLiteDialect already handle the DDL emission correctly without columnDefinition.
3. For AgentCronEntity.runOnce, change to:
     @Column(name = "runonce", nullable = false)
     @ColumnDefault("false")
     private boolean runOnce;
   Add the Hibernate @ColumnDefault annotation (org.hibernate.annotations.ColumnDefault).
   For SQLite, add a note in ArchitectureInvariants: SQLite stores BOOLEAN as INTEGER 0/1
   via Hibernate's built-in mapping — no converter needed, Hibernate handles it natively.
4. Add Architecture Invariant 5.22:
   "No JPA entity in arcclaw-core or arcclaw-persistence-api may use @Column(columnDefinition=...)
    All DDL customization must live in per-adapter Flyway migration scripts or @ColumnDefault."
    

=== GAP 2: BLOCKING FOR POSTGRES — SessionSchemaEnforcer Raw SQLite DDL ===
File: arcclaw-app/src/main/java/com/arcclaw/app/session/SessionSchemaEnforcer.java

This class issues raw native DDL at startup:
  "CREATE TABLE IF NOT EXISTS sessionregistry (id INTEGER PRIMARY KEY AUTOINCREMENT, ...)"

AUTOINCREMENT is SQLite-only syntax. PostgreSQL uses SERIAL or GENERATED ALWAYS AS IDENTITY.
This class is currently in arcclaw-app, which depends on arcclaw-persistence-sqlite at runtime.

Action (choose ONE, document the choice in ArchitectureInvariants):

Move to arcclaw-persistence-sqlite (recommended for now):
  1. Move SessionSchemaEnforcer to com.arcclaw.persistence.sqlite package.
  2. Keep the raw DDL for SQLite as-is (AUTOINCREMENT is fine for the SQLite adapter).
  3. When a PostgresSchemaEnforcer is added later, it issues the Postgres-compatible DDL.
  4. Add @IfBuildProfile or use CDI @Alternative to select the correct enforcer at runtime.
  5. Add Architecture Invariant 5.23:
     "All raw DDL CREATE TABLE statements are adapter-specific and must never live in
      arcclaw-core or arcclaw-app. Each persistence adapter module owns its schema DDL."

