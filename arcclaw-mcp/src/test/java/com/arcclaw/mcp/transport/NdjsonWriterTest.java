package com.arcclaw.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class NdjsonWriterTest {

    @Test
    void testWriteLineWritesBytesAndNewline() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String json = "{\"key\":\"value\"}";

        NdjsonWriter.writeLine(out, json);

        String result = out.toString(StandardCharsets.UTF_8);
        assertEquals(json + "\n", result);
    }

    @Test
    void testWriteLineFlushesStream() throws IOException {
        OutputStream out = mock(OutputStream.class);
        String json = "{}";

        NdjsonWriter.writeLine(out, json);

        verify(out, atLeastOnce()).flush();
    }

    @Test
    void testWriteLineThrowsNpeOnNullOut() {
        assertThrows(NullPointerException.class, () -> NdjsonWriter.writeLine(null, "{}"));
    }

    @Test
    void testWriteLineThrowsNpeOnNullJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(NullPointerException.class, () -> NdjsonWriter.writeLine(out, null));
    }

    @Test
    void testMultipleWriteLineCalls() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        NdjsonWriter.writeLine(out, "{\"id\":1}");
        NdjsonWriter.writeLine(out, "{\"id\":2}");

        String result = out.toString(StandardCharsets.UTF_8);
        assertEquals("{\"id\":1}\n" + "{\"id\":2}\n", result);
    }
}
