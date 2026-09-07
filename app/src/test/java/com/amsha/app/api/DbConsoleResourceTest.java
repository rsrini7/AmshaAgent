package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.amsha.app.ui.dto.DbQueryRequest;
import com.amsha.app.ui.dto.DbQueryResponse;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.core.audit.AuditService;
import com.amsha.core.audit.SecurityEventType;
import com.amsha.core.scope.WorkspaceContext;
import com.amsha.persistence.api.BootstrapStateDto;
import com.amsha.persistence.api.BootstrapStateStorePort;
import com.amsha.persistence.api.SessionCatalogEntry;
import com.amsha.persistence.api.SessionCatalogStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DbConsoleResourceTest {

    private DbConsoleResource resource;
    private DataSource dataSource;
    private AuditService auditService;
    private SecurityContext securityContext;
    private Instance<SessionCatalogStore> sessionStoreInstance;
    private SessionCatalogStore sessionStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resource = new DbConsoleResource();
        dataSource = mock(DataSource.class);
        auditService = mock(AuditService.class);
        securityContext = mock(SecurityContext.class);
        sessionStoreInstance = mock(Instance.class);
        sessionStore = mock(SessionCatalogStore.class);

        when(sessionStoreInstance.isResolvable()).thenReturn(true);
        when(sessionStoreInstance.get()).thenReturn(sessionStore);
        resource.sessionCatalogStore = sessionStoreInstance;

        SessionCatalogEntry validEntry = new SessionCatalogEntry(
                "entry-1",
                "ws-1",
                "chat",
                "c1",
                "s-1",
                "role",
                "goal",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");
        when(sessionStore.bySessionId("s-1")).thenReturn(Optional.of(validEntry));

        Instance<DataSource> dsInstance = mock(Instance.class);
        when(dsInstance.get()).thenReturn(dataSource);
        resource.dataSourceInstance = dsInstance;
        resource.auditService = auditService;
        resource.securityContext = securityContext;
        resource.runtimeBackend = "sqlite";
    }

    @Test
    void nonInternalCallerGetsForbidden() {
        when(securityContext.isUserInRole("internal")).thenReturn(false);

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT 1 FROM sqlite_master", 10));
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), resp.getStatus());
    }

    @Test
    void emptyOrNullSqlGetsBadRequest() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response resp1 = resource.executeQuery(new DbQueryRequest(null, 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp1.getStatus());

        Response resp2 = resource.executeQuery(new DbQueryRequest("   ", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp2.getStatus());
    }

    @Test
    void filesystemBackendReturnsGracefulInformativeResponse() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        resource.runtimeBackend = "fs";

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT 1 FROM sqlite_master", 10));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
        DbQueryResponse body = (DbQueryResponse) resp.getEntity();
        assertEquals(List.of("status"), body.columns());
        assertTrue(body.rows().get(0).get(0).toString().contains("AMSHA_PERSISTENCE_BACKEND=fs"));
    }

    @Test
    void multiStatementInjectionRejectedAndAuditedWithoutQueryPayload() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        String maliciousSql = "SELECT 1 FROM sqlite_master; DROP TABLE recall_messages;";
        Response resp = resource.executeQuery(new DbQueryRequest(maliciousSql, 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .recordMandatory(
                        eq("db-console"),
                        eq("test-operator"),
                        eq("operator"),
                        eq(SecurityEventType.UNAUTHORIZED_ACCESS),
                        msgCaptor.capture());

        String capturedMsg = msgCaptor.getValue();
        assertTrue(capturedMsg.contains("hash="));
        assertFalse(capturedMsg.contains(maliciousSql));
    }

    @Test
    void dangerousFunctionRejectedAndAudited() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT load_extension('evil.so');", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());

        verify(auditService)
                .recordMandatory(
                        eq("db-console"),
                        eq("test-operator"),
                        eq("operator"),
                        eq(SecurityEventType.UNAUTHORIZED_ACCESS),
                        anyString());
    }

    @Test
    void recursiveExplainDmlRejected() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response resp = resource.executeQuery(new DbQueryRequest("EXPLAIN INSERT INTO recall_messages VALUES(1);", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    }

    @Test
    void unallowedPragmaRejected() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);

        Response resp = resource.executeQuery(new DbQueryRequest("PRAGMA compile_options;", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
    }

    @Test
    void allowedPragmaAndMaskedColumns() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);

        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.createStatement()).thenReturn(pragmaStmt);
        when(pragmaStmt.executeQuery(anyString())).thenReturn(rs);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);

        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("cid");
        when(meta.getColumnLabel(2)).thenReturn("password_hash");

        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(1);
        when(rs.getObject(2)).thenReturn("plain-secret-password");

        Response resp = resource.executeQuery(new DbQueryRequest("PRAGMA table_info('identity_user')", 10));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

        DbQueryResponse body = (DbQueryResponse) resp.getEntity();
        assertEquals(List.of("cid", "password_hash"), body.columns());
        assertEquals(1, body.rows().size());
        assertEquals(1, body.rows().get(0).get(0));
        assertEquals("[REDACTED]", body.rows().get(0).get(1)); // sensitive column masked

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .recordMandatory(
                        eq("db-console"),
                        eq("test-operator"),
                        eq("operator"),
                        eq(SecurityEventType.SESSION_INSPECTED),
                        msgCaptor.capture());

        assertTrue(msgCaptor.getValue().contains("hash="));
        assertFalse(msgCaptor.getValue().contains("plain-secret-password"));
        verify(pragmaStmt).execute("PRAGMA query_only = ON;");
        verify(pragmaStmt).execute("PRAGMA query_only = OFF;");
    }

    @Test
    void pragmaRejectedOnPostgreSql() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("PostgreSQL");

        Response resp = resource.executeQuery(new DbQueryRequest("PRAGMA table_info('identity_user')", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        assertTrue(resp.getEntity().toString().contains("PRAGMA introspection is supported only on SQLite backends"));
    }

    @Test
    void informationSchemaWildcardForbiddenAndSpecificColumnsAllowed() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        // Wildcard on information_schema.tables is rejected
        Response wildcardResp =
                resource.executeQuery(new DbQueryRequest("SELECT * FROM information_schema.tables", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), wildcardResp.getStatus());
        assertTrue(wildcardResp.getEntity().toString().contains("Wildcard '*' is forbidden on schema metadata tables"));

        // Unapproved columns on information_schema.tables is rejected
        Response unapprovedResp = resource.executeQuery(
                new DbQueryRequest("SELECT table_name, non_existent FROM information_schema.tables", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), unapprovedResp.getStatus());
        assertTrue(unapprovedResp
                .getEntity()
                .toString()
                .contains("Access to column 'non_existent' in schema metadata is forbidden"));

        // Approved columns on information_schema.tables executes normally on PostgreSQL
        Connection conn = mock(Connection.class);
        Statement timeoutStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(conn.createStatement()).thenReturn(timeoutStmt);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("table_name");
        when(meta.getColumnLabel(2)).thenReturn("table_type");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn("session_catalog");
        when(rs.getObject(2)).thenReturn("BASE TABLE");

        Response okResp = resource.executeQuery(
                new DbQueryRequest("SELECT table_name, table_type FROM information_schema.tables", 10));
        assertEquals(Response.Status.OK.getStatusCode(), okResp.getStatus());
    }

    @Test
    void directTableAccessRejected() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT * FROM recall_messages", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        assertTrue(resp.getEntity().toString().contains("Direct access to data table 'recall_messages' is prohibited"));
    }

    @Test
    void sqliteExecutesQueryOnlyPragmaOnViewQuery() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.createStatement()).thenReturn(pragmaStmt);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("message");
        when(rs.next()).thenReturn(false);

        ScopedValue.where(WorkspaceContext.ACTIVE_WORKSPACE_ID, "ws-1")
                .where(WorkspaceContext.SESSION_ID, "s-1")
                .run(() -> {
                    Response resp =
                            resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 10));
                    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
                });

        verify(pragmaStmt).execute("PRAGMA query_only = ON;");
        verify(pragmaStmt).execute("PRAGMA query_only = OFF;");
    }

    @Test
    void postgreSqlConfiguresReadOnlyTransactionAndRollback() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Connection conn = mock(Connection.class);
        Statement timeoutStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(conn.createStatement()).thenReturn(timeoutStmt);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("message");
        when(rs.next()).thenReturn(false);

        ScopedValue.where(WorkspaceContext.ACTIVE_WORKSPACE_ID, "ws-1")
                .where(WorkspaceContext.SESSION_ID, "s-1")
                .run(() -> {
                    Response resp =
                            resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 10));
                    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
                });

        verify(conn).setAutoCommit(false);
        verify(conn).setReadOnly(true);
        verify(timeoutStmt).execute("SET LOCAL statement_timeout = 2000;");
        verify(conn).rollback();
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionOwnershipMismatchRejected() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Instance<SessionCatalogStore> storeInstance = mock(Instance.class);
        SessionCatalogStore store = mock(SessionCatalogStore.class);
        when(storeInstance.isResolvable()).thenReturn(true);
        when(storeInstance.get()).thenReturn(store);
        resource.sessionCatalogStore = storeInstance;

        SessionCatalogEntry entry = new SessionCatalogEntry(
                "entry-1",
                "other-workspace",
                "chat",
                "c1",
                "sess-1",
                "role",
                "goal",
                Instant.now(),
                Instant.now(),
                1,
                true,
                "ACTIVE");
        when(store.bySessionId("sess-1")).thenReturn(Optional.of(entry));

        ScopedValue.where(WorkspaceContext.SESSION_ID, "sess-1")
                .where(WorkspaceContext.ACTIVE_WORKSPACE_ID, "my-workspace")
                .run(() -> {
                    Response resp =
                            resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 10));
                    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
                    assertTrue(resp.getEntity().toString().contains("Session does not belong to active workspace"));
                });
    }

    @Test
    void sessionViewsFailClosedWithoutWorkspaceContext() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Response resp = resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        assertTrue(resp.getEntity().toString().contains("Active workspace ID is mandatory"));
    }

    @Test
    void sessionViewsFailClosedWhenStoreUnavailable() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(sessionStoreInstance.isResolvable()).thenReturn(false);

        ScopedValue.where(WorkspaceContext.ACTIVE_WORKSPACE_ID, "ws-1")
                .where(WorkspaceContext.SESSION_ID, "s-1")
                .run(() -> {
                    Response resp =
                            resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 10));
                    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
                    assertTrue(resp.getEntity().toString().contains("Session catalog store unavailable"));
                });
    }

    @Test
    void sessionViewsFailClosedWhenSessionNotFound() {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        when(sessionStore.bySessionId("s-unknown")).thenReturn(Optional.empty());

        ScopedValue.where(WorkspaceContext.ACTIVE_WORKSPACE_ID, "ws-1")
                .where(WorkspaceContext.SESSION_ID, "s-unknown")
                .run(() -> {
                    Response resp =
                            resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 10));
                    assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
                    assertTrue(resp.getEntity().toString().contains("Session not found in catalog"));
                });
    }

    @Test
    void rawJdbcExceptionTextNotDisclosedInAuditOrErrorPayload() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Connection conn = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenThrow(new SQLException("Raw leak of internal db path: /secret/tokens.db"));

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT 1 FROM sqlite_master", 10));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        String bodyText = resp.getEntity().toString();
        assertFalse(bodyText.contains("/secret/tokens.db"));
        assertTrue(bodyText.contains("Database execution error: query execution failed"));

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .recordMandatory(
                        eq("db-console"),
                        eq("test-operator"),
                        eq("operator"),
                        eq(SecurityEventType.DATABASE_QUERY_FAILED),
                        msgCaptor.capture());
        String auditMsg = msgCaptor.getValue();
        assertFalse(auditMsg.contains("/secret/tokens.db"));
        assertTrue(auditMsg.contains("code=DATABASE_EXECUTION_ERROR"));
    }

    @Test
    void unknownDatabaseProductFailsClosed() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Connection conn = mock(Connection.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("UnknownDB");

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT 1 FROM sqlite_master", 10));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        assertTrue(resp.getEntity().toString().contains("Unsupported database product"));
        verify(conn, Mockito.never()).prepareStatement(anyString());
    }

    @Test
    void exactLimitReturnsTruncatedFalse() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.createStatement()).thenReturn(pragmaStmt);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("message");

        // Exactly 2 rows returned, matching limit=2
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getObject(1)).thenReturn("msg1", "msg2");

        ScopedValue.where(WorkspaceContext.ACTIVE_WORKSPACE_ID, "ws-1")
                .where(WorkspaceContext.SESSION_ID, "s-1")
                .run(() -> {
                    Response resp =
                            resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 2));
                    assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());

                    DbQueryResponse body = (DbQueryResponse) resp.getEntity();
                    assertEquals(2, body.rows().size());
                    assertFalse(body.truncated());
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesWorkspaceFromBootstrapStateWhenContextUnbound() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Instance<BootstrapStateStorePort> bootstrapInstance = mock(Instance.class);
        BootstrapStateStorePort bootstrapStore = mock(BootstrapStateStorePort.class);
        when(bootstrapInstance.isResolvable()).thenReturn(true);
        when(bootstrapInstance.get()).thenReturn(bootstrapStore);
        resource.bootstrapStateStoreInstance = bootstrapInstance;

        BootstrapStateDto state = new BootstrapStateDto(
                "boot-1",
                BootstrapStateDto.STATUS_CLAIMED,
                "test-operator",
                "ws-1",
                "digest",
                Instant.now(),
                Instant.now(),
                1,
                Instant.now());
        when(bootstrapStore.getState()).thenReturn(Optional.of(state));

        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.createStatement()).thenReturn(pragmaStmt);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT id FROM session_catalog_view", 10));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    }

    @Test
    void resolvesWorkspaceAndSessionFromRequestBody() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.createStatement()).thenReturn(pragmaStmt);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("message");
        when(rs.next()).thenReturn(false);

        Response resp =
                resource.executeQuery(new DbQueryRequest("SELECT message FROM recent_traces_view", 10, "ws-1", "s-1"));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    }

    @Test
    void pragmaTableListSucceedsEvenIfSessionPassedWithoutWorkspace() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.createStatement()).thenReturn(pragmaStmt);
        when(pragmaStmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("name");
        when(rs.next()).thenReturn(false);

        Response resp = resource.executeQuery(new DbQueryRequest("PRAGMA table_list;", 10, null, "s-any"));
        assertEquals(Response.Status.OK.getStatusCode(), resp.getStatus());
    }

    @Test
    void mandatoryAuditFailureReturns500() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.createStatement()).thenReturn(pragmaStmt);
        when(pragmaStmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("name");
        when(rs.next()).thenReturn(false);

        doThrow(new RuntimeException("Audit disk error"))
                .when(auditService)
                .recordMandatory(eq("db-console"), any(), any(), eq(SecurityEventType.SESSION_INSPECTED), any());

        Response resp = resource.executeQuery(new DbQueryRequest("PRAGMA table_list;", 10));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());
        AmshaErrorResponse err = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Mandatory audit logging failed", err.error());
    }

    @Test
    void sqliteResetFailureDiscardsConnectionAndRethrows() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        Connection conn = mock(Connection.class);
        Statement pragmaStmt = mock(Statement.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        DatabaseMetaData dbMeta = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(dbMeta);
        when(dbMeta.getDatabaseProductName()).thenReturn("SQLite");
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(rs.next()).thenReturn(false);

        Statement resetStmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(pragmaStmt).thenReturn(resetStmt);
        doThrow(new SQLException("Failed to reset query_only")).when(resetStmt).execute("PRAGMA query_only = OFF;");

        Response resp =
                resource.executeQuery(new DbQueryRequest("SELECT id FROM session_catalog_view", 10, "ws-1", "s-1"));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), resp.getStatus());
        AmshaErrorResponse err = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Database execution error: query execution failed", err.error());
        verify(conn).abort(any());
    }

    @Test
    void errorAuditFailureFailsClosedWith500() throws Exception {
        when(securityContext.isUserInRole("internal")).thenReturn(true);
        Principal principal = () -> "test-operator";
        when(securityContext.getUserPrincipal()).thenReturn(principal);

        doThrow(new RuntimeException("Audit disk full"))
                .when(auditService)
                .recordMandatory(eq("db-console"), any(), any(), eq(SecurityEventType.UNAUTHORIZED_ACCESS), any());

        Response resp = resource.executeQuery(new DbQueryRequest("SELECT * FROM invalid_table_drop", 10));
        assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), resp.getStatus());
        AmshaErrorResponse err = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Mandatory audit logging failed", err.error());
    }
}
