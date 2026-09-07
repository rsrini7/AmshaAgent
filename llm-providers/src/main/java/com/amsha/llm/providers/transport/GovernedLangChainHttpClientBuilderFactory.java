package com.amsha.llm.providers.transport;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderFactory;

import java.util.concurrent.atomic.AtomicReference;

import com.amsha.security.http.GovernedTransportService;

/**
 * Service loader SPI factory for {@link GovernedLangChainHttpClientBuilder}.
 *
 * <p>Enables any LangChain4j provider SDK to discover and default to governed outbound HTTP
 * transport across the application.</p>
 */
public class GovernedLangChainHttpClientBuilderFactory implements HttpClientBuilderFactory {

    public static final String FACTORY_PROPERTY = "langchain4j.http.clientBuilderFactory";
    private static final AtomicReference<GovernedTransportService> DEFAULT_TRANSPORT = new AtomicReference<>();

    public static void ensureConfigured() {
        if (!GovernedLangChainHttpClientBuilderFactory.class.getName().equals(System.getProperty(FACTORY_PROPERTY))) {
            System.setProperty(FACTORY_PROPERTY, GovernedLangChainHttpClientBuilderFactory.class.getName());
        }
    }

    public static void setDefaultTransport(GovernedTransportService transport) {
        if (transport != null) {
            ensureConfigured();
        }
        DEFAULT_TRANSPORT.set(transport);
    }

    public static GovernedTransportService getDefaultTransport() {
        return DEFAULT_TRANSPORT.get();
    }

    @Override
    public HttpClientBuilder create() {
        return new GovernedLangChainHttpClientBuilder(DEFAULT_TRANSPORT.get());
    }
}
