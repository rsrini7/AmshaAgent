package com.arcclaw.persistence.sqlite.jdbi;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import com.arcclaw.common.util.LogUtil;
import com.arcclaw.core.util.TemporalUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import org.jboss.logging.Logger;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

@ApplicationScoped
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
            .registerColumnMapper(UUID.class, new UuidColumnMapper());
    }

    static class InstantArgumentFactory extends AbstractArgumentFactory<Instant> {
        InstantArgumentFactory() {
            super(Types.VARCHAR);
        }

        @Override
        protected Argument build(Instant value, ConfigRegistry config) {
            return (position, statement, ctx) ->
                statement.setString(position, TemporalUtils.formatIsoLex(value));
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
}
