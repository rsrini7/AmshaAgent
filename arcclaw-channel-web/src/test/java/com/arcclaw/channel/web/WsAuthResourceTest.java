package com.arcclaw.channel.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;

import java.util.Collections;
import java.util.Map;

import com.arcclaw.channel.api.ChannelServices;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WsAuthResourceTest {

    @Mock
    ChannelServices channelServices;

    @Mock
    WsTokenStore tokenStore;

    @Mock
    HttpHeaders httpHeaders;

    WsAuthResource resource;

    @BeforeEach
    void setUp() {
        resource = new WsAuthResource();
        setField(resource, "channelServices", channelServices);
        setField(resource, "tokenStore", tokenStore);
        setField(resource, "httpHeaders", httpHeaders);
    }

    @Test
    void issueTokenReturnsTokenWithValidApiKey() {
        String apiKey = "valid-api-key-123";
        when(channelServices.isValidIdentity(apiKey)).thenReturn(true);
        when(tokenStore.generateToken()).thenReturn("ws-token-abc");

        WsAuthResource.WsTokenResponse response = resource.issueToken(apiKey);

        assertNotNull(response);
        assertEquals("ws-token-abc", response.token);
    }

    @Test
    void issueTokenThrows401WithInvalidApiKey() {
        String apiKey = "invalid-key";
        when(channelServices.isValidIdentity(apiKey)).thenReturn(false);

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> resource.issueToken(apiKey));
        assertEquals(401, ex.getResponse().getStatus());
    }

    @Test
    void issueTokenThrows401WithNullApiKey() {
        Cookie cookie = null;
        when(httpHeaders.getCookies()).thenReturn(Collections.emptyMap());

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> resource.issueToken(null));
        assertEquals(401, ex.getResponse().getStatus());
    }

    @Test
    void issueTokenThrows401WithBlankApiKey() {
        when(httpHeaders.getCookies()).thenReturn(Collections.emptyMap());

        WebApplicationException ex = assertThrows(WebApplicationException.class, () -> resource.issueToken("  "));
        assertEquals(401, ex.getResponse().getStatus());
    }

    @Test
    void issueTokenFallsBackToCookieWhenHeaderMissing() {
        String cookieValue = "cookie-api-key";
        Cookie cookie = new Cookie("ARCCLAW_API_KEY", cookieValue);
        when(httpHeaders.getCookies()).thenReturn(Map.of("ARCCLAW_API_KEY", cookie));
        when(channelServices.isValidIdentity(cookieValue)).thenReturn(true);
        when(tokenStore.generateToken()).thenReturn("ws-token-from-cookie");

        WsAuthResource.WsTokenResponse response = resource.issueToken(null);

        assertNotNull(response);
        assertEquals("ws-token-from-cookie", response.token);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}
