package com.amsha.persistence.sqlite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;

import com.amsha.persistence.api.VectorIndexMetadataPort;
import com.amsha.persistence.api.VectorIndexNamespace;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

class VectorIngestionServiceTest {

    @Test
    void testIsVectorAvailable() {
        VectorIngestionService service = new VectorIngestionService();
        service.ftsInterceptor = mock(FtsConnectionInterceptor.class);
        service.vectorIndexMetadata = mock(VectorIndexMetadataPort.class);

        when(service.ftsInterceptor.isVectorSearchEnabled()).thenReturn(true);
        when(service.vectorIndexMetadata.isVectorEnabled(VectorIndexNamespace.ARCHIVAL))
                .thenReturn(true);

        assertTrue(service.isVectorAvailable());

        when(service.ftsInterceptor.isVectorSearchEnabled()).thenReturn(false);
        assertFalse(service.isVectorAvailable());
    }

    @Test
    void testIngestAndDeleteVectorErrorHandling() {
        VectorIngestionService service = new VectorIngestionService();
        service.jdbi = mock(Jdbi.class);
        doThrow(new RuntimeException("DB error")).when(service.jdbi).useHandle(any());

        CompletableFuture<Void> ingestFuture = service.ingestVector("chunk-1", new float[] {0.1f, 0.2f});
        assertNotNull(ingestFuture);
        assertDoesNotThrow(() -> ingestFuture.join());

        CompletableFuture<Void> deleteFuture = service.deleteVector("chunk-1");
        assertNotNull(deleteFuture);
        assertDoesNotThrow(() -> deleteFuture.join());

        service.shutdown();
    }
}
