package com.pesaguard.backend.analytics.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.analytics.application.UsageQueryService;
import com.pesaguard.backend.analytics.application.UsageQueryService.UsageSeries;
import com.pesaguard.backend.analytics.domain.UsageGranularity;
import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

/**
 * Usage analytics for the portal.
 *
 * <p>Read-only. There is deliberately no endpoint to write, edit, or delete usage
 * data: the only correct source of these numbers is the request stream itself,
 * and any write path would be a way to falsify a customer's usage.
 *
 * <p>Every route requires a portal session and {@code usage:read}. The
 * organization is taken from the authenticated principal, never from a parameter,
 * so there is no organization argument that could be pointed at another tenant.
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageQueryService queryService;

    public UsageController(UsageQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Usage over a time range, as a series plus totals.
     *
     * <p>Granularity defaults to whatever suits the requested span, and the
     * response states which was used so a caller never has to guess whether they
     * are reading minutes or days.
     */
    @GetMapping
    ApiResponse<UsageSeries> series(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UsageGranularity granularity,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID environmentId) {
        return ApiResponse.of(queryService.series(principal, from, to, granularity,
                projectId, environmentId));
    }

    /** Which endpoints this organization called, for a usage breakdown. */
    @GetMapping("/endpoints")
    ApiResponse<List<String>> endpoints(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "HOUR") UsageGranularity granularity) {
        return ApiResponse.of(queryService.endpoints(principal, from, to, granularity));
    }
}