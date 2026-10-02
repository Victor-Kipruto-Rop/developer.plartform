package com.pesaguard.backend.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.pesaguard.backend.analytics.domain.UsageBucket;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.analytics.infrastructure.UsageBucketRepository;
import com.pesaguard.backend.common.exception.BusinessException;
import com.pesaguard.backend.common.exception.UnauthorizedException;
import com.pesaguard.backend.rbac.domain.Permission;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Read-side authorization and tenant isolation.
 *
 * <p>The property that matters most: a caller can never retrieve another
 * tenant's usage. Asserted by checking the organization actually reaching the
 * repository, not merely that a filter ran.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UsageQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T14:37:52Z");

    @Mock
    private UsageBucketRepository bucketRepository;

    private UsageQueryService service;
    private final UUID organizationId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UsageQueryService(bucketRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(bucketRepository.findForOrganization(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private AuthenticatedUser withUsageRead() {
        return new AuthenticatedUser(UUID.randomUUID(), organizationId, UUID.randomUUID(),
                "user@example.com", "User", Set.of(Permission.USAGE_READ.value()));
    }

    private AuthenticatedUser withoutUsageRead() {
        return new AuthenticatedUser(UUID.randomUUID(), organizationId, UUID.randomUUID(),
                "user@example.com", "User", Set.of(Permission.PROJECT_READ.value()));
    }

    @Test
    void queriesAreAlwaysScopedToTheCallersOrganization() {
        service.series(withUsageRead(), NOW.minus(Duration.ofHours(6)), NOW,
                UsageGranularity.HOUR, null, null);

        // The caller's organization, never a caller-supplied value.
        verify(bucketRepository).findForOrganization(eq(organizationId), eq(UsageGranularity.HOUR),
                any(), any());
    }

    @Test
    void aCallerWithoutUsageReadIsRefused() {
        // Being logged in is not permission to read usage.
        assertThatThrownBy(() -> service.series(withoutUsageRead(), NOW.minusSeconds(60), NOW,
                UsageGranularity.HOUR, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("permission");
    }

    @Test
    void anUnauthenticatedCallerIsRefused() {
        assertThatThrownBy(() -> service.series(null, NOW.minusSeconds(60), NOW,
                UsageGranularity.HOUR, null, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aBackwardsRangeIsRejected() {
        assertThatThrownBy(() -> service.series(withUsageRead(), NOW, NOW.minusSeconds(60),
                UsageGranularity.HOUR, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anExcessiveRangeIsRejected() {
        // Without a cap, one request could ask for years of buckets.
        assertThatThrownBy(() -> service.series(withUsageRead(),
                NOW.minus(Duration.ofDays(400)), NOW, UsageGranularity.DAY, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("366");
    }

    @Test
    void granularityDefaultsToOneSuitingTheSpan() {
        // Reported rather than hidden, so a caller is never misled about which
        // resolution they were given.
        var result = service.series(withUsageRead(), NOW.minus(Duration.ofHours(6)), NOW,
                null, null, null);
        assertThat(result.granularity()).isEqualTo(UsageGranularity.HOUR);
    }

    @Test
    void anEmptyResultSummarisesToZeroRatherThanFailing() {
        var result = service.series(withUsageRead(), NOW.minusSeconds(60), NOW,
                UsageGranularity.HOUR, null, null);

        assertThat(result.summary().totalRequests()).isZero();
        assertThat(result.summary().errorRate()).isZero();
        assertThat(result.summary().errorRate()).isNotNaN();
        assertThat(result.points()).isEmpty();
    }

    @Test
    void endpointLookupIsAlsoTenantScoped() {
        when(bucketRepository.findDistinctEndpoints(any(), any(), any(), any()))
                .thenReturn(List.of("/api/v1/payments"));

        service.endpoints(withUsageRead(), NOW.minusSeconds(60), NOW, UsageGranularity.HOUR);

        verify(bucketRepository).findDistinctEndpoints(eq(organizationId),
                eq(UsageGranularity.HOUR), any(), any());
    }

    @Test
    void recommendedGranularityCoversEachRange() {
        assertThat(UsageAggregationService.recommendedFor(Duration.ofMinutes(30)))
                .isEqualTo(UsageGranularity.MINUTE);
        assertThat(UsageAggregationService.recommendedFor(Duration.ofHours(6)))
                .isEqualTo(UsageGranularity.HOUR);
        assertThat(UsageAggregationService.recommendedFor(Duration.ofDays(7)))
                .isEqualTo(UsageGranularity.DAY);
        assertThat(UsageAggregationService.recommendedFor(Duration.ofDays(90)))
                .isEqualTo(UsageGranularity.MONTH);
    }

    @Test
    void projectAndEnvironmentFiltersNarrowTheResult() {
        UUID otherProject = UUID.randomUUID();
        UsageBucket matching = bucket(UsageGranularity.HOUR, NOW, projectId, 5, 1);
        UsageBucket other = bucket(UsageGranularity.HOUR, NOW.plusSeconds(3600), otherProject, 9, 9);

        when(bucketRepository.findForOrganization(any(), any(), any(), any()))
                .thenReturn(List.of(matching, other));

        var result = service.series(withUsageRead(), NOW.minusSeconds(60),
                NOW.plusSeconds(7200), UsageGranularity.HOUR, projectId, null);

        // Filters narrow; they never widen past what the tenant query returned.
        assertThat(result.points()).hasSize(1);
        assertThat(result.points().get(0).totalRequests()).isEqualTo(5);
    }

    private UsageBucket bucket(UsageGranularity granularity, Instant windowStart,
            UUID project, long total, long failed) {
        UsageBucket created = UsageBucket.forWindow(granularity, windowStart, organizationId,
                project, environmentId, UUID.randomUUID(), null, "/api/v1/payments", "POST");
        created.replaceAggregates(new UsageBucket.Aggregates(total, total - failed, failed,
                failed, 0, total * 10L, 10, 10, 10, 10, 100L, windowStart), 0);
        return created;
    }
}