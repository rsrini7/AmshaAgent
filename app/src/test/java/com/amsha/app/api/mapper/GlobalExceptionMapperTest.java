package com.amsha.app.api.mapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import com.amsha.core.api.dto.AmshaErrorResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GlobalExceptionMapperTest {

    GlobalExceptionMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new GlobalExceptionMapper();
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("/api/v1/test");
        var field = GlobalExceptionMapper.class.getDeclaredField("uriInfo");
        field.setAccessible(true);
        field.set(mapper, uriInfo);
    }

    @Test
    void returns500ForGenericException() {
        Response resp = mapper.toResponse(new RuntimeException("something broke"));
        assertEquals(500, resp.getStatus());
        var body = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Internal Server Error", body.error());
        assertTrue(body.detail().contains("something broke"));
    }

    @Test
    void preservesStatusForWebApplicationException() {
        var wae = new WebApplicationException("not found", 404);
        Response resp = mapper.toResponse(wae);
        assertEquals(404, resp.getStatus());
        var body = (AmshaErrorResponse) resp.getEntity();
        assertEquals("Request Failed", body.error());
    }

    @Test
    void responseIncludesPath() {
        Response resp = mapper.toResponse(new RuntimeException("err"));
        var body = (AmshaErrorResponse) resp.getEntity();
        assertEquals("/api/v1/test", body.path());
    }
}
