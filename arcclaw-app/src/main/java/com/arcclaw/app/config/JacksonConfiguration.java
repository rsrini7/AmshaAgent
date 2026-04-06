package com.arcclaw.app.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Produces;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.jackson.ObjectMapperCustomizer;

@ApplicationScoped
public class JacksonConfiguration {

    @Produces
    @ApplicationScoped
    public ObjectMapperCustomizer disableEscaping() {
        return (ObjectMapper mapper) -> {
            // Disables the escaping of non-ASCII characters
            mapper.getFactory().disable(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature());
        };
    }
}
