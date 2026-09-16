package com.amsha.persistence.sqlite.jdbi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Direct coverage for the eight nested argument factories and column mappers in
 * {@link JdbiProducer}.
 *
 * <p>These are registered only by the CDI producer, so nothing in the existing suite ever calls
 * {@code build()} or {@code map()} on them directly — 22 uncovered lines. Each is exercised here
 * against a mocked {@link PreparedStatement}/{@link ResultSet}, which is enough because the
 * interesting behaviour is the value encoding, not the JDBC round trip.
 *
 * <p>Lives in the {@code jdbi} package because the factories' constructors and {@code build}
 * methods are package-private.
 */
class JdbiProducerFactoriesTest {

    private static final ConfigRegistry CONFIG = new ConfigRegistry();

    private StatementContext context;

    @BeforeEach
    void setUp() {
        // None of the factories or mappers read the statement context; a mock keeps it honest.
        context = mock(StatementContext.class);
    }

    // ---------- Instant ----------

    @Test
    void instantArgumentWritesTheLexicographicalIsoForm() throws Exception {
        Argument argument =
                new JdbiProducer.InstantArgumentFactory().build(Instant.parse("2026-01-02T03:04:05.678Z"), CONFIG);
        PreparedStatement statement = mock(PreparedStatement.class);

        argument.apply(1, statement, context);

        verify(statement).setString(1, "2026-01-02T03:04:05.678Z");
    }

    @Test
    void instantColumnMapperParsesTheStoredText() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("2026-01-02T03:04:05.678Z");
        ColumnMapper<Instant> mapper = new JdbiProducer.InstantColumnMapper();

        assertEquals(Instant.parse("2026-01-02T03:04:05.678Z"), mapper.map(rs, 1, context));
    }

    @Test
    void instantColumnMapperPassesThroughNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);

        assertEquals(null, new JdbiProducer.InstantColumnMapper().map(rs, 1, context));
    }

    // ---------- JSON map ----------

    @Test
    void jsonArgumentWritesNullForAnEmptyOrNullMap() throws Exception {
        JdbiProducer.JsonArgumentFactory factory = new JdbiProducer.JsonArgumentFactory(new ObjectMapper());
        PreparedStatement statement = mock(PreparedStatement.class);

        factory.build(null, CONFIG).apply(1, statement, context);
        verify(statement).setNull(1, Types.VARCHAR);

        factory.build(Map.of(), CONFIG).apply(1, statement, context);
        verify(statement, Mockito.times(2)).setNull(1, Types.VARCHAR);
    }

    @Test
    void jsonArgumentSerializesANonEmptyMap() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        Argument argument = new JdbiProducer.JsonArgumentFactory(new ObjectMapper()).build(Map.of("k", "v"), CONFIG);

        argument.apply(1, statement, context);

        verify(statement).setString(1, "{\"k\":\"v\"}");
    }

    @Test
    void jsonArgumentWrapsASerializationFailure() {
        ObjectMapper failing = mock(ObjectMapper.class);
        try {
            when(failing.writeValueAsString(ArgumentMatchers.any())).thenThrow(new IllegalStateException("boom"));
        } catch (Exception e) {
            throw new AssertionError("stubbing failed", e);
        }
        Argument argument = new JdbiProducer.JsonArgumentFactory(failing).build(Map.of("k", "v"), CONFIG);
        PreparedStatement statement = mock(PreparedStatement.class);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> argument.apply(1, statement, context));
        assertTrue(thrown.getMessage().contains("Failed to serialize JSON argument"));
    }

    @Test
    void jsonColumnMapperReturnsAnEmptyMapForNullOrBlankText() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ColumnMapper<Map<String, Object>> mapper = new JdbiProducer.JsonColumnMapper(new ObjectMapper());

        when(rs.getString(1)).thenReturn(null);
        assertEquals(Map.of(), mapper.map(rs, 1, context));

        when(rs.getString(1)).thenReturn("   ");
        assertEquals(Map.of(), mapper.map(rs, 1, context));
    }

    @Test
    void jsonColumnMapperParsesJsonText() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("{\"k\":\"v\"}");

        assertEquals(Map.of("k", "v"), new JdbiProducer.JsonColumnMapper(new ObjectMapper()).map(rs, 1, context));
    }

    @Test
    void jsonColumnMapperWrapsMalformedJson() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("not-json");
        ColumnMapper<Map<String, Object>> mapper = new JdbiProducer.JsonColumnMapper(new ObjectMapper());

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> mapper.map(rs, 1, context));
        assertTrue(thrown.getMessage().contains("Failed to parse JSON column"));
    }

    // ---------- UUID ----------

    @Test
    void uuidArgumentWritesTheTwoLongsAsSixteenBytes() throws Exception {
        UUID value = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        Argument argument = new JdbiProducer.UuidArgumentFactory().build(value, CONFIG);
        PreparedStatement statement = mock(PreparedStatement.class);

        argument.apply(1, statement, context);

        ByteBuffer expected = ByteBuffer.allocate(16);
        expected.putLong(value.getMostSignificantBits());
        expected.putLong(value.getLeastSignificantBits());
        verify(statement).setBytes(eq(1), ArgumentMatchers.eq(expected.array()));
    }

    @Test
    void uuidColumnMapperRoundTripsTheBytes() throws Exception {
        UUID value = new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L);
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(value.getMostSignificantBits());
        bytes.putLong(value.getLeastSignificantBits());

        ResultSet rs = mock(ResultSet.class);
        when(rs.getBytes(1)).thenReturn(bytes.array());

        assertEquals(value, new JdbiProducer.UuidColumnMapper().map(rs, 1, context));
    }

    @Test
    void uuidColumnMapperPassesThroughNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBytes(anyInt())).thenReturn(null);

        assertEquals(null, new JdbiProducer.UuidColumnMapper().map(rs, 1, context));
    }

    // ---------- float[] (sqlite-vec) ----------

    @Test
    void floatArrayArgumentWritesLittleEndianFloats() throws Exception {
        Argument argument = new JdbiProducer.FloatArrayArgumentFactory().build(new float[] {1.0f, -2.5f}, CONFIG);
        PreparedStatement statement = mock(PreparedStatement.class);

        argument.apply(1, statement, context);

        ByteBuffer expected = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        expected.putFloat(1.0f).putFloat(-2.5f);
        assertArrayEquals(expected.array(), captureBytes(statement));
    }

    @Test
    void floatArrayArgumentWritesAnEmptyBlobForAnEmptyVector() throws Exception {
        Argument argument = new JdbiProducer.FloatArrayArgumentFactory().build(new float[0], CONFIG);
        PreparedStatement statement = mock(PreparedStatement.class);

        argument.apply(1, statement, context);

        assertArrayEquals(new byte[0], captureBytes(statement));
    }

    private static byte[] captureBytes(PreparedStatement statement) throws Exception {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(statement).setBytes(eq(1), captor.capture());
        return captor.getValue();
    }
}
