package com.amsha.persistence.sqlite.jdbi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.amsha.common.util.LogUtil;
import com.amsha.common.util.TemporalUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.properties.IfBuildProperty;

@ApplicationScoped
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "sqlite", enableIfMissing = true)
public class JdbiProducer {

    @Inject
    AgroalDataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    @Produces
    @ApplicationScoped
    public Jdbi jdbi() {
        return Jdbi.create(dataSource)
                .installPlugin(new SqlObjectPlugin())
                .registerArgument(new InstantArgumentFactory())
                .registerColumnMapper(Instant.class, new InstantColumnMapper())
                .registerArgument(new JsonArgumentFactory(objectMapper))
                .registerColumnMapper(new JsonColumnMapper(objectMapper))
                .registerArgument(new UuidArgumentFactory())
                .registerColumnMapper(UUID.class, new UuidColumnMapper())
                .registerArgument(new FloatArrayArgumentFactory());
    }

    static class InstantArgumentFactory extends AbstractArgumentFactory<Instant> {
        InstantArgumentFactory() {
            super(Types.VARCHAR);
        }

        @Override
        protected Argument build(Instant value, ConfigRegistry config) {
            return (position, statement, ctx) -> statement.setString(position, TemporalUtils.formatIsoLex(value));
        }
    }

    static class InstantColumnMapper implements ColumnMapper<Instant> {
        @Override
        public Instant map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
            String text = r.getString(columnNumber);
            return text != null ? TemporalUtils.parseIsoLex(text) : null;
        }
    }

    static class JsonArgumentFactory extends AbstractArgumentFactory<Map<String, Object>> {
        private final ObjectMapper mapper;

        JsonArgumentFactory(ObjectMapper mapper) {
            super(Types.VARCHAR);
            this.mapper = mapper;
        }

        @Override
        protected Argument build(Map<String, Object> value, ConfigRegistry config) {
            return (position, statement, ctx) -> {
                if (value == null || value.isEmpty()) {
                    statement.setNull(position, Types.VARCHAR);
                } else {
                    try {
                        statement.setString(position, mapper.writeValueAsString(value));
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to serialize JSON argument", e);
                    }
                }
            };
        }
    }

    static class JsonColumnMapper implements ColumnMapper<Map<String, Object>> {
        private final ObjectMapper mapper;
        private static final Logger log = Logger.getLogger(JsonColumnMapper.class);

        JsonColumnMapper(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Map<String, Object> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
            String json = r.getString(columnNumber);
            if (json == null || json.isBlank()) return Map.of();
            try {
                return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warnf("Failed to parse JSON column: %s", LogUtil.rootMessage(e));
                throw new RuntimeException("Failed to parse JSON column", e);
            }
        }
    }

    static class UuidArgumentFactory extends AbstractArgumentFactory<UUID> {
        UuidArgumentFactory() {
            super(Types.BLOB);
        }

        @Override
        protected Argument build(UUID value, ConfigRegistry config) {
            return (pos, stmt, ctx) -> {
                byte[] bytes = new byte[16];
                ByteBuffer bb = ByteBuffer.wrap(bytes);
                bb.putLong(value.getMostSignificantBits());
                bb.putLong(value.getLeastSignificantBits());
                stmt.setBytes(pos, bytes);
            };
        }
    }

    static class UuidColumnMapper implements ColumnMapper<UUID> {
        @Override
        public UUID map(ResultSet rs, int columnNumber, StatementContext ctx) throws SQLException {
            byte[] bytes = rs.getBytes(columnNumber);
            if (bytes == null) return null;
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            return new UUID(bb.getLong(), bb.getLong());
        }
    }

    /**
     * Serializes {@code float[]} as a little-endian binary blob for sqlite-vec.
     * <p>
     * sqlite-vec 0.1.x expects 32-bit little-endian floats for {@code float[N]} columns.
     * JSON string serialization works but is significantly slower in the native C extension.
     * This factory eliminates JSON parsing overhead, making vector search ~10x faster.
     */
    static class FloatArrayArgumentFactory extends AbstractArgumentFactory<float[]> {
        FloatArrayArgumentFactory() {
            super(Types.BLOB);
        }

        @Override
        protected Argument build(float[] value, ConfigRegistry config) {
            return (pos, stmt, ctx) -> {
                ByteBuffer bb = ByteBuffer.allocate(value.length * 4);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                for (float f : value) {
                    bb.putFloat(f);
                }
                stmt.setBytes(pos, bb.array());
            };
        }
    }
}
