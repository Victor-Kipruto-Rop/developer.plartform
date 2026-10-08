package com.pesaguard.backend.feedback.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.feedback.application.FeedbackService;
import com.pesaguard.backend.feedback.domain.FeedbackStatus;
import com.pesaguard.backend.feedback.domain.FeedbackType;
import com.pesaguard.backend.security.principals.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<FeedbackItemView>> create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateFeedbackRequest request) {
        return ResponseEntity.status(201)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(service.create(principal, request, idempotencyKey)));
    }

    @GetMapping
    ApiResponse<FeedbackPageView> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) FeedbackType type,
            @RequestParam(required = false) FeedbackStatus status,
            @RequestParam(required = false) String q) {
        return ApiResponse.of(service.list(principal, page, pageSize, type, status, q));
    }

    @GetMapping("/{reference}")
    ApiResponse<FeedbackItemView> get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String reference) {
        return ApiResponse.of(service.get(principal, reference));
    }

    @GetMapping("/{reference}/comments")
    ApiResponse<FeedbackCommentListView> comments(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String reference) {
        return ApiResponse.of(service.comments(principal, reference));
    }

    @PostMapping("/{reference}/comments")
    ResponseEntity<ApiResponse<FeedbackCommentView>> comment(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String reference,
            @Valid @RequestBody CreateFeedbackCommentRequest request) {
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.of(service.addDeveloperComment(principal, reference, request.body())));
    }

    @PostMapping("/{reference}/reopen")
    ApiResponse<FeedbackItemView> reopen(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable String reference) {
        return ApiResponse.of(service.reopen(principal, reference));
    }
}
