package com.amsha.persistence.postgres;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.amsha.persistence.api.PersistenceBackend;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@Unremovable
@IfBuildProperty(name = PersistenceBackend.PROPERTY, stringValue = PersistenceBackend.POSTGRES)
public class PostgresSessionCatalogAdapter implements SessionCatalogStore {
    private final Jdbi jdbi;

    @Inject
    public PostgresSessionCatalogAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    protected PostgresSessionCatalogAdapter() {
        this(null);
    }

    @Override
    public void record(SessionCatalogEntry e) {
        jdbi.useTransaction(h -> insert(h, e, e.seq() > 0 ? e.seq() : next(h, e), e.active(), e.status(), false));
    }

    @Override
    public void touch(String sid, String ok, String ct, String ci, String role, String goal) {
        jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE session_catalog SET last_used_at=:now, role=COALESCE(NULLIF(:role,''),role), goal_preview=COALESCE(NULLIF(:goal,''),goal_preview) WHERE session_id=:sid")
                .bind("now", Instant.now())
                .bind("role", role)
                .bind("goal", goal)
                .bind("sid", sid)
                .execute());
    }

    @Override
    public Optional<SessionCatalogEntry> resolveActive(String ok, String ct, String ci) {
        return scoped(
                "SELECT * FROM session_catalog WHERE owner_key=:ok AND channel_type=:ct AND channel_id=:ci AND active=TRUE",
                ok,
                ct,
                ci);
    }

    @Override
    public void activateNew(SessionCatalogEntry e) {
        jdbi.useTransaction(h -> {
            h.createUpdate(
                            "UPDATE session_catalog SET active=FALSE,status='INACTIVE' WHERE owner_key=:ok AND channel_type=:ct AND channel_id=:ci AND active=TRUE")
                    .bind("ok", e.ownerKey())
                    .bind("ct", e.channelType())
                    .bind("ci", e.channelId())
                    .execute();
            insert(h, e, e.seq() > 0 ? e.seq() : next(h, e), true, "ACTIVE", true);
        });
    }

    private void insert(Handle h, SessionCatalogEntry e, int seq, boolean active, String status, boolean activate) {
        h.createUpdate(
                        "INSERT INTO session_catalog (id,owner_key,channel_type,channel_id,session_id,role,goal_preview,created_at,last_used_at,seq,active,status) VALUES (:id,:ok,:ct,:ci,:sid,:role,:goal,:created,:used,:seq,:active,:status) ON CONFLICT(session_id) DO UPDATE SET role=excluded.role,goal_preview=excluded.goal_preview,last_used_at=excluded.last_used_at,active=CASE WHEN :activate THEN TRUE ELSE session_catalog.active END,status=CASE WHEN :activate THEN 'ACTIVE' ELSE session_catalog.status END")
                .bind("id", e.id())
                .bind("ok", e.ownerKey())
                .bind("ct", e.channelType())
                .bind("ci", e.channelId())
                .bind("sid", e.sessionId())
                .bind("role", e.role())
                .bind("goal", e.goalPreview())
                .bind("created", e.createdAt())
                .bind("used", e.lastUsedAt())
                .bind("seq", seq)
                .bind("active", active)
                .bind("status", status)
                .bind("activate", activate)
                .execute();
    }

    @Override
    public void setActive(String sid, boolean active) {
        if (active) bySessionId(sid).ifPresent(this::activateNew);
        else
            jdbi.useHandle(h -> h.createUpdate(
                            "UPDATE session_catalog SET active=FALSE,status='INACTIVE' WHERE session_id=:sid")
                    .bind("sid", sid)
                    .execute());
    }

    @Override
    public List<SessionCatalogEntry> listByScope(String ok, String ct, String ci) {
        return scopedList(
                "SELECT * FROM session_catalog WHERE owner_key=:ok AND channel_type=:ct AND channel_id=:ci ORDER BY seq DESC",
                ok,
                ct,
                ci);
    }

    @Override
    public List<SessionCatalogEntry> listInactiveBefore(Instant cutoff) {
        return jdbi.withHandle(
                h -> h.createQuery("SELECT * FROM session_catalog WHERE status='INACTIVE' AND last_used_at < :cutoff")
                        .bind("cutoff", cutoff)
                        .map((r, c) -> map(r))
                        .list());
    }

    @Override
    public Optional<SessionCatalogEntry> latestByScope(String ok, String ct, String ci) {
        return scoped(
                "SELECT * FROM session_catalog WHERE owner_key=:ok AND channel_type=:ct AND channel_id=:ci ORDER BY last_used_at DESC LIMIT 1",
                ok,
                ct,
                ci);
    }

    @Override
    public Optional<SessionCatalogEntry> bySessionId(String sid) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM session_catalog WHERE session_id=:sid")
                .bind("sid", sid)
                .map((r, c) -> map(r))
                .findOne());
    }

    @Override
    public Optional<SessionCatalogEntry> bySequence(String ok, String ct, String ci, int seq) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT * FROM session_catalog WHERE owner_key=:ok AND channel_type=:ct AND channel_id=:ci AND seq=:seq")
                .bind("ok", ok)
                .bind("ct", ct)
                .bind("ci", ci)
                .bind("seq", seq)
                .map((r, c) -> map(r))
                .findOne());
    }

    @Override
    public void archive(String sid) {
        jdbi.useHandle(
                h -> h.createUpdate("UPDATE session_catalog SET active=FALSE,status='ARCHIVED' WHERE session_id=:sid")
                        .bind("sid", sid)
                        .execute());
    }

    private int next(Handle h, SessionCatalogEntry e) {
        return h.createQuery(
                        "SELECT COALESCE(MAX(seq),0)+1 FROM session_catalog WHERE owner_key=:ok AND channel_type=:ct AND channel_id=:ci")
                .bind("ok", e.ownerKey())
                .bind("ct", e.channelType())
                .bind("ci", e.channelId())
                .mapTo(Integer.class)
                .one();
    }

    private Optional<SessionCatalogEntry> scoped(String sql, String ok, String ct, String ci) {
        return jdbi.withHandle(h -> h.createQuery(sql)
                .bind("ok", ok)
                .bind("ct", ct)
                .bind("ci", ci)
                .map((r, c) -> map(r))
                .findOne());
    }

    private List<SessionCatalogEntry> scopedList(String sql, String ok, String ct, String ci) {
        return jdbi.withHandle(h -> h.createQuery(sql)
                .bind("ok", ok)
                .bind("ct", ct)
                .bind("ci", ci)
                .map((r, c) -> map(r))
                .list());
    }

    private SessionCatalogEntry map(ResultSet r) throws SQLException {
        return new SessionCatalogEntry(
                r.getString("id"),
                r.getString("owner_key"),
                r.getString("channel_type"),
                r.getString("channel_id"),
                r.getString("session_id"),
                r.getString("role"),
                r.getString("goal_preview"),
                r.getObject("created_at", OffsetDateTime.class).toInstant(),
                r.getObject("last_used_at", OffsetDateTime.class).toInstant(),
                r.getInt("seq"),
                r.getBoolean("active"),
                r.getString("status"));
    }
}
