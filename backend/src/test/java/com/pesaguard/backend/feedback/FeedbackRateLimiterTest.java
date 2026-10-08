package com.pesaguard.backend.feedback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pesaguard.backend.feedback.application.FeedbackRateLimiter;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

class FeedbackRateLimiterTest {

    @Test
    void reportsDeniedOnceTheDatabaseBackedHourlyLimitIsExceeded() {
        EntityManager entityManager = mock(EntityManager.class);
        Query cleanup = mock(Query.class);
        Query counter = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(cleanup, counter);
        when(cleanup.executeUpdate()).thenReturn(0);
        when(counter.setParameter(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(counter);
        when(counter.getSingleResult()).thenReturn(11);

        FeedbackRateLimiter limiter = new FeedbackRateLimiter(entityManager);

        assertFalse(limiter.allow(UUID.randomUUID(), "CREATE", 10));
    }
}
