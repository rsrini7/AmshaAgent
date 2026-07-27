package com.amsha.core.routing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amsha.core.agent.IntentClassificationService;
import com.amsha.core.security.DestructiveVocabularyFilter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntentDetectorServiceUnitTest {

    @Mock
    IntentClassificationService intentDetector;

    @Mock
    DestructiveVocabularyFilter destructiveFilter;

    @InjectMocks
    IntentDetectorService service;

    @Test
    void testClassifyRetryCommand() {
        when(intentDetector.isRetryCommand("retry")).thenReturn(true);
        assertEquals("__CONTINUE__", service.classify("retry"));
    }

    @Test
    void testClassifyContinuationCommand() {
        when(intentDetector.isRetryCommand("continue")).thenReturn(false);
        when(intentDetector.isContinuationCommand("continue")).thenReturn(true);
        assertEquals("__CONTINUE__", service.classify("continue"));
    }
}
