package com.amsha.persistence.postgres.jdbi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;

import com.amsha.common.util.LogUtil;
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
@IfBuildProperty(name = "amsha.persistence.backend", stringValue = "postgres")
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
                .registerArgument(new JsonArgumentFactory(objectMapper))
                .registerColumnMapper(new JsonColumnMapper(objectMapper));
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
}
