package com.pesaguard.backend.feedback.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pesaguard.backend.common.api.ApiResponse;
import com.pesaguard.backend.feedback.application.FeedbackService;
import com.pesaguard.backend.feedback.domain.FeedbackAssignmentTeam;
import com.pesaguard.backend.feedback.domain.FeedbackPriority;
import com.pesaguard.backend.feedback.domain.FeedbackStatus;
import com.pesaguard.backend.feedback.domain.FeedbackType;
import com.pesaguard.backend.platformadmin.domain.AuthenticatedOperator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@Validated
@RequestMapping("/internal/feedback")
public class OperatorFeedbackController {

    private final FeedbackService service;

    public OperatorFeedbackController(FeedbackService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<AdminFeedbackPageView> list(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) FeedbackType type,
            @RequestParam(required = false) FeedbackStatus status,
            @RequestParam(required = false) FeedbackPriority priority,
            @RequestParam(required = false) FeedbackAssignmentTeam team,
            @RequestParam(required = false) String q) {
        return ApiResponse.of(service.listForOperator(operator, page, pageSize,
                organizationId, userId, projectId, type, status, priority, team, q));
    }

    @GetMapping("/{reference}")
    ApiResponse<AdminFeedbackDetailView> get(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference) {
        return ApiResponse.of(service.getForOperator(operator, reference));
    }

    @GetMapping("/{reference}/comments")
    ApiResponse<AdminFeedbackCommentListView> comments(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference) {
        return ApiResponse.of(service.commentsForOperator(operator, reference));
    }

    @PatchMapping("/{reference}")
    ApiResponse<AdminFeedbackDetailView> update(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference,
            @Valid @RequestBody UpdateFeedbackRequest request) {
        return ApiResponse.of(service.updateForOperator(operator, reference, request));
    }

    @PostMapping("/{reference}/assign")
    ApiResponse<AdminFeedbackDetailView> assign(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference,
            @Valid @RequestBody AssignFeedbackRequest request) {
        return ApiResponse.of(service.assignForOperator(operator, reference, request.team()));
    }

    @PostMapping("/{reference}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<FeedbackCommentView> comment(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference,
            @Valid @RequestBody CreateFeedbackCommentRequest request) {
        return ApiResponse.of(service.addOperatorComment(operator, reference, request.body()));
    }

    @PostMapping("/{reference}/internal-notes")
    ApiResponse<AdminFeedbackDetailView> internalNote(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference,
            @Valid @RequestBody InternalNoteRequest request) {
        return ApiResponse.of(service.addInternalNote(operator, reference, request.body()));
    }

    @PostMapping("/{reference}/resolve")
    ApiResponse<AdminFeedbackDetailView> resolve(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference) {
        return ApiResponse.of(service.updateForOperator(operator, reference,
                new UpdateFeedbackRequest(FeedbackStatus.RESOLVED, null)));
    }

    @PostMapping("/{reference}/close")
    ApiResponse<AdminFeedbackDetailView> close(
            @AuthenticationPrincipal AuthenticatedOperator operator,
            @PathVariable String reference) {
        return ApiResponse.of(service.updateForOperator(operator, reference,
                new UpdateFeedbackRequest(FeedbackStatus.CLOSED, null)));
    }

    public record InternalNoteRequest(@NotBlank @Size(max = 4000) String body) {
    }
}
