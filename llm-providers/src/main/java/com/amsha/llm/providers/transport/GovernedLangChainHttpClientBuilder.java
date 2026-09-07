package com.amsha.llm.providers.transport;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;

import java.time.Duration;

import com.amsha.security.http.GovernedTransportService;

/**
 * Builder for {@link GovernedLangChainHttpClient}.
 */
public class GovernedLangChainHttpClientBuilder implements HttpClientBuilder {

    private Duration connectTimeout;
    private Duration readTimeout;
    private GovernedTransportService governedTransport;

    public GovernedLangChainHttpClientBuilder() {
        this(null);
    }

    public GovernedLangChainHttpClientBuilder(GovernedTransportService governedTransport) {
        this.governedTransport = governedTransport;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        this.readTimeout = timeout;
        return this;
    }

    public GovernedLangChainHttpClientBuilder governedTransport(GovernedTransportService governedTransport) {
        this.governedTransport = governedTransport;
        return this;
    }

    @Override
    public HttpClient build() {
        return new GovernedLangChainHttpClient(connectTimeout, readTimeout, governedTransport);
    }
}
