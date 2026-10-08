package com.pesaguard.backend.feedback.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

@Service
public class FeedbackRateLimiter {

    private final EntityManager entityManager;

    public FeedbackRateLimiter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(java.util.UUID userId, String action, int limit) {
        entityManager.createNativeQuery("""
                delete from feedback_rate_limits
                where window_started_at < date_trunc('hour', now()) - interval '48 hours'
                """).executeUpdate();
        Number count = (Number) entityManager.createNativeQuery("""
                insert into feedback_rate_limits(user_id, action, window_started_at, request_count)
                values (:userId, :action, date_trunc('hour', now()), 1)
                on conflict (user_id, action, window_started_at)
                do update set request_count = feedback_rate_limits.request_count + 1
                returning request_count
                """)
                .setParameter("userId", userId)
                .setParameter("action", action)
                .getSingleResult();
        return count.intValue() <= limit;
    }
}
