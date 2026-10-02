package com.pesaguard.backend.analytics.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.analytics.domain.ApiRequestEvent;

public interface ApiRequestEventRepository extends JpaRepository<ApiRequestEvent, UUID> {

    boolean existsByRequestId(String requestId);

    Optional<ApiRequestEvent> findByRequestId(String requestId);

    /**
     * Events inside a half-open window, bounded by {@code organizationId}.
     *
     * <p>The organization predicate is not optional. Every query from this
     * repository is tenant-scoped, and a query without it would return another
     * tenant's traffic.
     *
     * <p>Range is {@code [from, to)} so an event on a boundary belongs to exactly
     * one window.
     */
    @Query("""
            select e from ApiRequestEvent e
            where e.organizationId = :organizationId
              and e.occurredAt >= :from
              and e.occurredAt < :to
            order by e.occurredAt asc
            """)
    List<ApiRequestEvent> findInWindow(@Param("organizationId") UUID organizationId,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /**
     * Distinct dimension sets present in a window, so the rollup knows what to
     * aggregate without scanning the raw events first.
     */
    @Query("""
            select distinct e.organizationId, e.projectId, e.environmentId,
                   e.apiKeyId, e.oauthApplicationId, e.endpoint, e.method
            from ApiRequestEvent e
            where e.occurredAt >= :from and e.occurredAt < :to
            """)
    List<Object[]> findDistinctDimensions(@Param("from") Instant from, @Param("to") Instant to);
}