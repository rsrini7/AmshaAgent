package com.amsha.persistence.postgres.jdbi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit coverage for the two JSON bridges {@link JdbiProducer} registers on the shared {@code Jdbi}.
 *
 * <p>Why this class exists: both are nested, package-private types reachable only through
 * {@link JdbiProducer#jdbi()}, which needs an {@code AgroalDataSource}. No test ever produced one, so the
 * whole of {@code JsonArgumentFactory.build}'s returned lambda and the whole of {@code JsonColumnMapper.map}
 * sat uncovered — including the anonymous {@code TypeReference} subclass, whose one line is the
 * clearest evidence that {@code map} had never executed at all.
 *
 * <p>The two are covered here through their real entry points rather than by reflection: build an
 * {@link Argument} and apply it to a mocked {@link PreparedStatement}, and call {@code map} with a mocked
 * {@link ResultSet}. The interesting assertions are the three-way split in each — empty/absent input must be
 * a no-op rather than a literal {@code "null"} or a crash, and malformed input must fail loudly rather than
 * silently degrade to an empty map.
 */
class JdbiProducerJsonMappingTest {

    private ObjectMapper mapper;
    private StatementContext context;
    private JdbiProducer.JsonArgumentFactory argumentFactory;
    private ColumnMapper<Map<String, Object>> columnMapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        context = mock(StatementContext.class);
        argumentFactory = new JdbiProducer.JsonArgumentFactory(mapper);
        columnMapper = new JdbiProducer.JsonColumnMapper(mapper);
    }

    // =========================================================================
    // JsonArgumentFactory — Map -> VARCHAR
    // =========================================================================

    @Test
    @DisplayName("an empty map is bound as SQL NULL, not as the string '{}'")
    void emptyMapIsBoundAsNull() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        argumentFactory.build(Map.of(), new ConfigRegistry()).apply(1, statement, context);

        verify(statement).setNull(1, Types.VARCHAR);
    }

    @Test
    @DisplayName("a null map is bound as SQL NULL")
    void nullMapIsBoundAsNull() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        argumentFactory.build(null, new ConfigRegistry()).apply(1, statement, context);

        verify(statement).setNull(1, Types.VARCHAR);
    }

    @Test
    @DisplayName("a populated map is serialized to its JSON text")
    void populatedMapIsSerialized() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);

        argumentFactory.build(Map.of("k", "v"), new ConfigRegistry()).apply(1, statement, context);

        verify(statement).setString(1, "{\"k\":\"v\"}");
    }

    @Test
    @DisplayName("a bind failure surfaces as a RuntimeException, not as a swallowed argument")
    void bindFailureIsWrapped() throws SQLException {
        PreparedStatement statement = mock(PreparedStatement.class);
        doThrow(new SQLException("connection closed")).when(statement).setString(eq(1), any());
        Argument argument = argumentFactory.build(Map.of("k", "v"), new ConfigRegistry());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> argument.apply(1, statement, context));

        assertEquals("Failed to serialize JSON argument", thrown.getMessage());
        assertTrue(thrown.getCause() instanceof SQLException);
    }

    // =========================================================================
    // JsonColumnMapper — VARCHAR -> Map
    // =========================================================================

    @Test
    @DisplayName("a JSON object column is parsed into a map")
    void jsonObjectColumnIsParsed() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("{\"a\":1,\"b\":\"two\"}");

        Map<String, Object> parsed = columnMapper.map(resultSet, 1, context);

        assertEquals(1, parsed.get("a"));
        assertEquals("two", parsed.get("b"));
    }

    @Test
    @DisplayName("a null column becomes an empty map")
    void nullColumnBecomesAnEmptyMap() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn(null);

        assertTrue(columnMapper.map(resultSet, 1, context).isEmpty());
    }

    @Test
    @DisplayName("a blank column becomes an empty map")
    void blankColumnBecomesAnEmptyMap() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("   ");

        assertTrue(columnMapper.map(resultSet, 1, context).isEmpty());
    }

    @Test
    @DisplayName("malformed JSON fails loudly instead of degrading to an empty map")
    void malformedJsonIsRejected() throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString(1)).thenReturn("{not json");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> columnMapper.map(resultSet, 1, context));

        assertEquals("Failed to parse JSON column", thrown.getMessage());
    }
}
