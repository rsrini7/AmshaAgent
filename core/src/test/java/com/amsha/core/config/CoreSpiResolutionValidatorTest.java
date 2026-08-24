package com.amsha.core.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Collections;
import java.util.Set;

import com.amsha.core.sandbox.SandboxExecutionPort;
import com.amsha.core.tools.search.ToolSearchPort;
import com.amsha.llm.api.ChatModelCreationPort;

import org.junit.jupiter.api.Test;

class CoreSpiResolutionValidatorTest {

    interface TestSpi {}

    static class ProviderA implements TestSpi {}

    static class ProviderB implements TestSpi {}

    @Test
    void testZeroProvidersReturnsEmpty() {
        BeanManager bm = mock(BeanManager.class);
        when(bm.getBeans(Object.class)).thenReturn(Collections.emptySet());

        var result = CoreSpiResolutionValidator.resolveOptional(bm, TestSpi.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void testNullBeanManagerOrSpiReturnsEmpty() {
        assertTrue(
                CoreSpiResolutionValidator.resolveOptional(null, TestSpi.class).isEmpty());
        assertTrue(CoreSpiResolutionValidator.resolveOptional(mock(BeanManager.class), null)
                .isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSingleProviderResolvesViaGetReference() {
        BeanManager bm = mock(BeanManager.class);
        Bean<ProviderA> bean = mock(Bean.class);
        doReturn(ProviderA.class).when(bean).getBeanClass();
        doReturn(Set.of(ProviderA.class, TestSpi.class)).when(bean).getTypes();

        when(bm.getBeans(Object.class)).thenReturn(Set.of((Bean<?>) bean));
        doReturn(bean).when(bm).resolve(anySet());
        CreationalContext<ProviderA> cc = mock(CreationalContext.class);
        when(bm.createCreationalContext(bean)).thenReturn(cc);
        ProviderA expectedInstance = new ProviderA();
        when(bm.getReference(bean, TestSpi.class, cc)).thenReturn(expectedInstance);

        var result = CoreSpiResolutionValidator.resolveOptional(bm, TestSpi.class);
        assertTrue(result.isPresent());
        assertSame(expectedInstance, result.get());
        verify(bm, times(1)).resolve(anySet());
        verify(bm, times(1)).getReference(bean, TestSpi.class, cc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSingleDisabledOrShadowProviderReturnsEmpty() {
        BeanManager bm = mock(BeanManager.class);
        Bean<ProviderA> bean = mock(Bean.class);
        doReturn(ProviderA.class).when(bean).getBeanClass();
        doReturn(Set.of(ProviderA.class, TestSpi.class)).when(bean).getTypes();

        when(bm.getBeans(Object.class)).thenReturn(Set.of((Bean<?>) bean));
        // Simulate CDI resolve returning null (bean is disabled or shadow/unselected alternative)
        doReturn(null).when(bm).resolve(anySet());

        var result = CoreSpiResolutionValidator.resolveOptional(bm, TestSpi.class);
        assertTrue(result.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMultipleProvidersThrowsIllegalStateExceptionWithSortedNames() {
        BeanManager bm = mock(BeanManager.class);
        Bean<ProviderA> beanA = mock(Bean.class);
        doReturn(ProviderA.class).when(beanA).getBeanClass();
        doReturn(Set.of(ProviderA.class, TestSpi.class)).when(beanA).getTypes();

        Bean<ProviderB> beanB = mock(Bean.class);
        doReturn(ProviderB.class).when(beanB).getBeanClass();
        doReturn(Set.of(ProviderB.class, TestSpi.class)).when(beanB).getTypes();

        when(bm.getBeans(Object.class)).thenReturn(Set.of((Bean<?>) beanB, (Bean<?>) beanA));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> CoreSpiResolutionValidator.resolveOptional(bm, TestSpi.class));

        assertTrue(ex.getMessage().contains("Multiple implementations detected"));
        assertTrue(ex.getMessage().contains(ProviderA.class.getName()));
        assertTrue(ex.getMessage().contains(ProviderB.class.getName()));
        // Ensure sorted order: ProviderA comes before ProviderB
        int idxA = ex.getMessage().indexOf(ProviderA.class.getName());
        int idxB = ex.getMessage().indexOf(ProviderB.class.getName());
        assertTrue(idxA < idxB, "Provider names should be sorted alphabetically");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testMultipleProvidersWithActiveAlternativeResolvesSelectedBean() {
        BeanManager bm = mock(BeanManager.class);
        Bean<ProviderA> beanA = mock(Bean.class);
        doReturn(ProviderA.class).when(beanA).getBeanClass();
        doReturn(Set.of(ProviderA.class, TestSpi.class)).when(beanA).getTypes();

        Bean<ProviderB> beanB = mock(Bean.class);
        doReturn(ProviderB.class).when(beanB).getBeanClass();
        doReturn(Set.of(ProviderB.class, TestSpi.class)).when(beanB).getTypes();

        when(bm.getBeans(Object.class)).thenReturn(Set.of((Bean<?>) beanB, (Bean<?>) beanA));
        // Simulate CDI prioritizing/selecting beanB as active alternative
        doReturn(beanB).when(bm).resolve(anySet());

        CreationalContext<ProviderB> cc = mock(CreationalContext.class);
        when(bm.createCreationalContext(beanB)).thenReturn(cc);
        ProviderB expectedInstance = new ProviderB();
        when(bm.getReference(beanB, TestSpi.class, cc)).thenReturn(expectedInstance);

        var result = CoreSpiResolutionValidator.resolveOptional(bm, TestSpi.class);
        assertTrue(result.isPresent());
        assertSame(expectedInstance, result.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testUnrelatedBeansAreFilteredOut() {
        BeanManager bm = mock(BeanManager.class);
        Bean<String> unrelatedBean = mock(Bean.class);
        doReturn(String.class).when(unrelatedBean).getBeanClass();
        doReturn(Set.of(String.class)).when(unrelatedBean).getTypes();

        when(bm.getBeans(Object.class)).thenReturn(Set.of((Bean<?>) unrelatedBean));

        var result = CoreSpiResolutionValidator.resolveOptional(bm, TestSpi.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void testValidateCardinalityMethod() {
        BeanManager bm = mock(BeanManager.class);
        when(bm.getBeans(Object.class)).thenReturn(Collections.emptySet());

        CoreSpiResolutionValidator validator = new CoreSpiResolutionValidator();
        validator.beanManager = bm;

        assertDoesNotThrow(() -> validator.validateCardinality(SandboxExecutionPort.class));
        assertDoesNotThrow(() -> validator.validateCardinality(ToolSearchPort.class));
        assertDoesNotThrow(() -> validator.validateCardinality(ChatModelCreationPort.class));
        assertDoesNotThrow(validator::validate);
    }
}
