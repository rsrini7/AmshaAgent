Replace with Flyway (recommended for long-term):
  1. Add quarkus-flyway to arcclaw-persistence-sqlite pom.xml.
  2. Create arcclaw-persistence-sqlite/src/main/resources/db/migration/V1__sessionregistry.sql:
       CREATE TABLE IF NOT EXISTS sessionregistry (
         id INTEGER PRIMARY KEY AUTOINCREMENT,
         channeltype TEXT NOT NULL,
         channelid TEXT NOT NULL,
         userid TEXT NOT NULL,
         sessionid TEXT NOT NULL UNIQUE,
         createdat TEXT NOT NULL,
         CONSTRAINT uq_session_triplet UNIQUE (channeltype, channelid, userid)
       );
  3. Delete SessionSchemaEnforcer entirely.
  4. For a future Postgres adapter, add a separate V1__sessionregistry.sql under
     arcclaw-persistence-postgres with GENERATED ALWAYS AS IDENTITY syntax.
  5. Add Architecture Invariant 5.23:
     "Schema migrations are managed by Flyway, scoped per persistence adapter module.
      No class may issue CREATE TABLE via raw JDBC or EntityManager at runtime."

this is just one sample.. need to check entire repo for this migration.