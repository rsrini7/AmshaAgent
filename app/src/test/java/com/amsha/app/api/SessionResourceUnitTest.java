package com.amsha.app.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.Collections;

import com.amsha.app.security.ApiKeyRegistry;
import com.amsha.persistence.api.WebSessionStorePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionResourceUnitTest {

    @Mock
    ApiKeyRegistry apiKeyRegistry;

    @Mock
    WebSessionStorePort webSessionStore;

    @Mock
    HttpHeaders httpHeaders;

    @InjectMocks
    SessionResource resource;

    @BeforeEach
    void setUp() {
        resource.httpHeaders = httpHeaders;
        lenient().when(httpHeaders.getCookies()).thenReturn(Collections.emptyMap());
    }

    @Test
    void testRotateSessionNoCookie() {
        Response response = resource.rotateSession();
        assertEquals(401, response.getStatus());
    }
}
