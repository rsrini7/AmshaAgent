package com.arcclaw.app.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Produces;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
            
            // § Resilience: Treat unknown enum values as null instead of crashing.
            // This prevents failures when LLM providers (like Gemini) add new 
            // finish reasons or status codes not yet in the LangChain4j library.
            mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
        };
    }
}
