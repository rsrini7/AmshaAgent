package com.amsha.app.api;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.amsha.app.db.DbQueryValidator;
import com.amsha.app.db.DbValidationException;
import com.amsha.app.ui.dto.DbQueryRequest;
import com.amsha.app.ui.dto.DbQueryResponse;
import com.amsha.common.util.LogUtil;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.ActiveWorkspaceContext;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.IdentityStorePort;
import com.amsha.persistence.api.SessionCatalogStore;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.smallrye.common.annotation.RunOnVirtualThread;

/**
 * Read-Only Database Console endpoint for internal operators.
 *
 * <p>Enforces AST-level query validation, two-tier parameterized view isolation, SQLite/PostgreSQL
 * transaction safety boundaries, bounded concurrency, row clamping, column masking, and mandatory audit logging.
 */
@ApplicationScoped
@Path("/admin/db")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DbConsoleResource {

    private static final Logger log = Logger.getLogger(DbConsoleResource.class);
    private static final Semaphore QUERY_SEMAPHORE = new Semaphore(2);
    private static final int MAX_ROWS = 100;
    private static final int DEFAULT_ROWS = 50;

    @ConfigProperty(name = "amsha.persistence.backend", defaultValue = "sqlite")
    String runtimeBackend;

    @Inject
    Instance<DataSource> dataSourceInstance;

    @Inject
    Instance<SessionCatalogStore> sessionCatalogStore;

    @Inject
    Instance<ActiveWorkspaceContext> activeWorkspaceContextInstance;

    @Inject
    Instance<IdentityStorePort> identityStoreInstance;

    @Inject
    Instance<BootstrapStateStorePort> bootstrapStateStoreInstance;

    @Inject
    AuditService auditService;

    @Context
    SecurityContext securityContext;

    @POST
    @Path("/query")
    public Response executeQuery(DbQueryRequest request) {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Internal operator role required for database console"))
                    .build();
        }

        if (request == null || request.sql() == null || request.sql().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Query SQL is required"))
                    .build();
        }

        String callerUserId = resolveUserId();
        String rawSql = request.sql();
        String queryHash = hashQuery(rawSql);

        if ("fs".equalsIgnoreCase(runtimeBackend) || "filesystem".equalsIgnoreCase(runtimeBackend)) {
            try {
                recordMandatoryAudit(
                        callerUserId,
                        SecurityEventType.SESSION_INSPECTED,
                        "DB query unavailable: hash=" + queryHash + " code=FILESYSTEM_BACKEND");
            } catch (Exception ex) {
                log.errorf("DbConsole mandatory audit failure for hash=%s: %s", queryHash, LogUtil.rootMessage(ex));
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(new AmshaErrorResponse("Mandatory audit logging failed"))
                        .build();
            }
            return Response.ok(new DbQueryResponse(
                            List.of("status"),
                            List.of(
                                    List.of(
                                            "Relational SQL queries are not supported on flat-file persistence backend (AMSHA_PERSISTENCE_BACKEND=fs)")),
                            0L,
                            false))
                    .build();
        }

        String sessId = DbConsoleTenantResolver.resolveSessionId(request);

        String wsId;
        DbQueryValidator.ValidatedQuery validatedQuery;
        try {
            wsId = DbConsoleTenantResolver.resolveWorkspaceId(
                    request,
                    callerUserId,
                    identityStoreInstance,
                    activeWorkspaceContextInstance,
                    bootstrapStateStoreInstance);
            validatedQuery = DbQueryValidator.validateAndScope(rawSql, wsId, sessId);
            if (validatedQuery.requiresSessionVerification()) {
                verifySessionOwnership(wsId, sessId, callerUserId);
            }
        } catch (DbValidationException ex) {
            recordErrorAudit(
                    callerUserId,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "DB query rejected: hash=" + queryHash + " code=VALIDATION_FAILED");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse(ex.getMessage()))
                    .build();
        }

        int limit = request.limit() != null ? Math.min(Math.max(1, request.limit()), MAX_ROWS) : DEFAULT_ROWS;

        boolean acquired;
        try {
            acquired = QUERY_SEMAPHORE.tryAcquire(2, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Response.status(429)
                    .entity(new AmshaErrorResponse("Interrupted while waiting for database query permit"))
                    .build();
        }

        if (!acquired) {
            return Response.status(429)
                    .entity(new AmshaErrorResponse("Database console query concurrency limit reached (max 2)"))
                    .build();
        }

        long startNs = System.nanoTime();
        try {
            DataSource ds = dataSourceInstance.get();
            DbResultMapper.DbQueryResult result = executeOnDataSource(ds, validatedQuery, limit);
            long executionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            try {
                recordMandatoryAudit(
                        callerUserId,
                        SecurityEventType.SESSION_INSPECTED,
                        "DB query executed: hash=" + queryHash + " rowCount="
                                + result.rows().size() + " durationMs=" + executionMs);
            } catch (Exception ex) {
                log.errorf("DbConsole mandatory audit failure for hash=%s: %s", queryHash, LogUtil.rootMessage(ex));
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(new AmshaErrorResponse("Mandatory audit logging failed"))
                        .build();
            }

            return Response.ok(new DbQueryResponse(result.columns(), result.rows(), executionMs, result.truncated()))
                    .build();
        } catch (DbValidationException ex) {
            recordErrorAudit(
                    callerUserId,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "DB query rejected: hash=" + queryHash + " code=VALIDATION_FAILED");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse(ex.getMessage()))
                    .build();
        } catch (SQLException ex) {
            recordErrorAudit(
                    callerUserId,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "DB query execution failure: hash=" + queryHash + " code=DATABASE_EXECUTION_ERROR");
            log.warnf(
                    "DbConsole query failed for hash=%s: sqlState=%s errorCode=%d",
                    queryHash, ex.getSQLState(), ex.getErrorCode());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new AmshaErrorResponse("Database execution error: query execution failed"))
                    .build();
        } catch (RuntimeException ex) {
            recordErrorAudit(
                    callerUserId,
                    SecurityEventType.UNAUTHORIZED_ACCESS,
                    "DB query execution failure: hash=" + queryHash + " code=DATABASE_UNAVAILABLE");
            log.warnf("DbConsole database unavailable hash=%s: %s", queryHash, LogUtil.rootMessage(ex));
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new AmshaErrorResponse("Database execution unavailable"))
                    .build();
        } finally {
            QUERY_SEMAPHORE.release();
        }
    }

    /**
     * Verifies that the requested session belongs to the active workspace.
     * Fails closed if the session store is unresolvable, the session record is missing,
     * or the owner key does not match the active workspace.
     */
    private void verifySessionOwnership(String workspaceId, String sessionId, String callerUserId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new DbValidationException("Active workspace ID is required when session ID is specified");
        }
        if (sessionCatalogStore == null || !sessionCatalogStore.isResolvable()) {
            throw new DbValidationException(
                    "Session catalog store unavailable; cannot verify tenant session ownership");
        }
        var entry = sessionCatalogStore
                .get()
                .bySessionId(sessionId)
                .orElseThrow(() -> new DbValidationException("Session not found in catalog: " + sessionId));
        String ownerKey = entry.ownerKey();
        boolean matches = ownerKey != null
                && (ownerKey.equals(workspaceId)
                        || ownerKey.equals(callerUserId)
                        || isUserInWorkspace(ownerKey, workspaceId));
        if (!matches) {
            throw new DbValidationException("Session does not belong to active workspace");
        }
    }

    private boolean isUserInWorkspace(String userId, String workspaceId) {
        if (identityStoreInstance != null && identityStoreInstance.isResolvable()) {
            try {
                return identityStoreInstance
                        .get()
                        .findMembership(workspaceId, userId)
                        .map(m -> "ACTIVE".equalsIgnoreCase(m.status()))
                        .orElse(false);
            } catch (Exception _) {
                return false;
            }
        }
        return false;
    }

    private DbResultMapper.DbQueryResult executeOnDataSource(
            DataSource ds, DbQueryValidator.ValidatedQuery query, int limit) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            String productName =
                    (conn.getMetaData() != null) ? conn.getMetaData().getDatabaseProductName() : "";
            boolean isSqlite =
                    productName != null && productName.toLowerCase(Locale.ROOT).contains("sqlite");
            boolean isPostgres =
                    productName != null && productName.toLowerCase(Locale.ROOT).contains("postgres");
            if (!isSqlite && !isPostgres) {
                throw new DbValidationException("Unsupported database product");
            }

            if (query.isPragma()) {
                if (!isSqlite) {
                    throw new DbValidationException("PRAGMA introspection is supported only on SQLite backends");
                }
                try (Statement pragmaStmt = conn.createStatement()) {
                    pragmaStmt.setQueryTimeout(2);
                    pragmaStmt.setMaxRows(limit + 1);
                    try (ResultSet rs = pragmaStmt.executeQuery(query.sql())) {
                        return DbResultMapper.mapResultSet(rs, limit);
                    }
                }
            }

            boolean sqliteQueryOnlyConfigured = isSqlite;
            try {
                if (isSqlite) {
                    try (Statement pragmaStmt = conn.createStatement()) {
                        pragmaStmt.execute("PRAGMA query_only = ON;");
                    }
                } else {
                    conn.setAutoCommit(false);
                    conn.setReadOnly(true);
                    try (Statement timeoutStmt = conn.createStatement()) {
                        timeoutStmt.execute("SET LOCAL statement_timeout = 2000;");
                    }
                }

                try (PreparedStatement stmt = conn.prepareStatement(query.sql())) {
                    stmt.setQueryTimeout(2);
                    stmt.setMaxRows(limit + 1);

                    List<Object> params = query.parameters();
                    for (int i = 0; i < params.size(); i++) {
                        stmt.setObject(i + 1, params.get(i));
                    }

                    try (ResultSet rs = stmt.executeQuery()) {
                        return DbResultMapper.mapResultSet(rs, limit);
                    }
                }
            } finally {
                if (isPostgres) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackEx) {
                        log.warnf("Failed PostgreSQL transaction rollback: %s", LogUtil.rootMessage(rollbackEx));
                    }
                    try {
                        conn.setReadOnly(false);
                        conn.setAutoCommit(true);
                    } catch (SQLException resetEx) {
                        // A pooled connection left read-only/auto-commit-off corrupts later writers,
                        // so drop it from the pool instead of returning it.
                        log.warnf(
                                "Failed to restore PostgreSQL connection state; discarding connection: %s",
                                LogUtil.rootMessage(resetEx));
                        discardConnection(conn);
                    }
                }
                if (sqliteQueryOnlyConfigured) {
                    try (Statement resetStmt = conn.createStatement()) {
                        resetStmt.execute("PRAGMA query_only = OFF;");
                    } catch (SQLException resetEx) {
                        // query_only is a per-connection setting: returning a connection stuck ON
                        // would break every subsequent write on it, so evict it from the pool.
                        log.warnf(
                                "Failed to reset SQLite query_only on pooled connection; discarding: %s",
                                LogUtil.rootMessage(resetEx));
                        discardConnection(conn);
                    }
                }
            }
        }
    }

    /** Aborts and flushes a corrupted pooled connection so Agroal evicts it instead of reusing it. */
    private void discardConnection(Connection conn) {
        try {
            conn.abort(Runnable::run);
        } catch (Throwable t) {
            log.warnf("Failed to abort corrupted connection: %s", LogUtil.rootMessage(t));
            try {
                conn.close();
            } catch (SQLException closeEx) {
                log.debugf("Failed to close connection after abort failure: %s", LogUtil.rootMessage(closeEx));
            }
        }
        try {
            if (dataSourceInstance != null && dataSourceInstance.isResolvable()) {
                DataSource ds = dataSourceInstance.get();
                if (ds instanceof AgroalDataSource agroalDs) {
                    agroalDs.flush(AgroalDataSource.FlushMode.INVALID);
                }
            }
        } catch (Throwable t) {
            log.debugf("Failed to flush invalid pool connections: %s", LogUtil.rootMessage(t));
        }
    }

    private void recordMandatoryAudit(String callerUserId, SecurityEventType type, String message) {
        if (auditService != null) {
            auditService.recordMandatory("db-console", callerUserId, "operator", type, message);
        }
    }

    private void recordErrorAudit(String callerUserId, SecurityEventType type, String message) {
        if (auditService != null) {
            try {
                auditService.recordMandatory("db-console", callerUserId, "operator", type, message);
            } catch (Exception e) {
                log.warnf(e, "DbConsole: error audit logging failed; message=%s", message);
            }
        }
    }

    private static String hashQuery(String sql) {
        if (sql == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return "sha256-error";
        }
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }

    private String resolveUserId() {
        if (securityContext != null && securityContext.getUserPrincipal() != null) {
            return securityContext.getUserPrincipal().getName();
        }
        return WorkspaceContext.getUserIdOrUnknown();
    }
}
