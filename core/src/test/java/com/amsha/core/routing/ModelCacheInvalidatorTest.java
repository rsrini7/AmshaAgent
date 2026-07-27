package com.amsha.core.routing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class ModelCacheInvalidatorTest {

    @Test
    void forwardsConnectionChangeToPool() {
        ChatModelPool pool = mock(ChatModelPool.class);
        ModelCacheInvalidator invalidator = new ModelCacheInvalidator(pool);
        invalidator.onConnectionChanged("conn-1");
        verify(pool).invalidateByConnection("conn-1");
        verify(pool).invalidateProfileRoutes();
    }

    @Test
    void forwardsProfileChangeToPool() {
        ChatModelPool pool = mock(ChatModelPool.class);
        ModelCacheInvalidator invalidator = new ModelCacheInvalidator(pool);
        invalidator.onProfileChanged("profile-1");
        verify(pool).invalidateByProfile("profile-1");
        verify(pool).invalidateProfileRoutes();
    }

    @Test
    void ignoresNullIds() {
        ChatModelPool pool = mock(ChatModelPool.class);
        ModelCacheInvalidator invalidator = new ModelCacheInvalidator(pool);
        invalidator.onConnectionChanged(null);
        invalidator.onProfileChanged(null);
        verify(pool, never()).invalidateByConnection(any());
    }
}
