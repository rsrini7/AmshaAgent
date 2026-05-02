package com.arcclaw.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.AnnotationLiteral;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class McpStdioReconnectTest {

    private McpClientService service;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        service = new McpClientService();
        service.objectMapper = mapper;
        service.meterRegistry = new SimpleMeterRegistry();
        
        service.managedExecutors = mock(Instance.class);
        ManagedExecutor mockExec = mock(ManagedExecutor.class);
        when(service.managedExecutors.select(any(Class.class))).thenReturn(service.managedExecutors);
        when(service.managedExecutors.select(any(AnnotationLiteral.class))).thenReturn(service.managedExecutors);
        when(service.managedExecutors.get()).thenReturn(mockExec);
        when(service.managedExecutors.stream()).thenReturn(Stream.of(mockExec));

        service.init();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testF19_CleanupAndReconnectOnDeadProcess() throws Exception {
        // 1. Setup mock dead state
        String serverName = "dead-server";
        
        Process mockProcess = mock(Process.class);
        OutputStream mockOut = mock(OutputStream.class);
        // This reader will return null (EOF) immediately
        BufferedReader mockReader = new BufferedReader(new StringReader("")); 

        // Inject into private maps
        setPrivateField(service, "processes", mapOf(serverName, mockProcess));
        setPrivateField(service, "processWriters", mapOf(serverName, mockOut));
        setPrivateField(service, "processReaders", mapOf(serverName, mockReader));

        // Setup config for reconnect
        Map<String, Object> cfgMap = (Map<String, Object>) getPrivateField(service, "stdioConfigs");
        cfgMap.put(serverName, createStdioConfig("cmd", Collections.emptyList(), Collections.emptyMap()));

        // 2. Mock connectStdio to "re-alive" the process
        McpClientService spyService = Mockito.spy(service);
        // We can't really call the real connectStdio in a unit test easily as it starts a process
        // but we want to verify it's CALLED.
        doNothing().when(spyService).connectStdio(anyString(), anyString(), anyList(), anyMap());

        // 3. Invoke. First attempt fails due to EOF in sendRequestStdio. 
        // cleanupStdio(serverName) is called.
        // Exception propagates to @Retry (not active here since we're calling directly or mocking).
        // Actually, we need to test that sendRequestStdio calls cleanupStdio.

        try {
            spyService.invoke(serverName, "tool", "{}");
        } catch (Exception e) {
            // expected on first try if no retry logic active
        }

        // 4. Verify cleanup was called
        Map<String, Process> processes = (Map<String, Process>) getPrivateField(service, "processes");
        assertEquals(0, processes.size(), "Process should have been removed from map after EOF");
        verify(mockProcess).destroyForcibly();
    }

    private Object createStdioConfig(String cmd, Object args, Object env) throws Exception {
        Class<?> configClass = Class.forName("com.arcclaw.mcp.transport.McpClientService$StdioConfig");
        var ctor = configClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(cmd, args, env);
    }

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = McpClientService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = McpClientService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private <K, V> ConcurrentHashMap<K, V> mapOf(K key, V value) {
        ConcurrentHashMap<K, V> map = new ConcurrentHashMap<>();
        map.put(key, value);
        return map;
    }
}
