package com.pesaguard.backend.analytics.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageGranularity;

public interface UsageBucketRepository extends JpaRepository<UsageBucket, UUID> {

    Optional<UsageBucket> findByGranularityAndWindowStartAndOrganizationIdAndProjectIdAndEnvironmentIdAndApiKeyIdAndOauthApplicationIdAndEndpointAndMethod(
            UsageGranularity granularity, Instant windowStart, UUID organizationId,
            UUID projectId, UUID environmentId, UUID apiKeyId, UUID oauthApplicationId,
            String endpoint, String method);

    /**
     * Every bucket for an organization in a granularity and window range.
     *
     * <p>Always organization-scoped. Usage is tenant data; a query without this
     * predicate would expose one tenant's traffic to another.
     */
    @Query("""
            select b from UsageBucket b
            where b.organizationId = :organizationId
              and b.granularity = :granularity
              and b.windowStart >= :from
              and b.windowStart < :to
            order by b.windowStart asc
            """)
    List<UsageBucket> findForOrganization(
            @Param("organizationId") UUID organizationId,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Distinct endpoints used by an organization, for the usage breakdown. */
    @Query("""
            select distinct b.endpoint from UsageBucket b
            where b.organizationId = :organizationId
              and b.granularity = :granularity
              and b.windowStart >= :from and b.windowStart < :to
            """)
    List<String> findDistinctEndpoints(
            @Param("organizationId") UUID organizationId,
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Windows that exist at this granularity, so the worker knows what to rebuild. */
    @Query("""
            select distinct b.windowStart from UsageBucket b
            where b.granularity = :granularity
              and b.windowStart >= :from and b.windowStart < :to
            """)
    List<Instant> findWindowStarts(
            @Param("granularity") UsageGranularity granularity,
            @Param("from") Instant from,
            @Param("to") Instant to);
}