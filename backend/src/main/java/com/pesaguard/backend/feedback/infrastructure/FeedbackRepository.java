package com.pesaguard.backend.feedback.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pesaguard.backend.feedback.domain.Feedback;
import com.pesaguard.backend.feedback.domain.FeedbackPriority;
import com.pesaguard.backend.feedback.domain.FeedbackStatus;
import com.pesaguard.backend.feedback.domain.FeedbackType;
import jakarta.persistence.LockModeType;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {

    Optional<Feedback> findByPublicReferenceAndOrganizationIdAndUserId(
            String publicReference, UUID organizationId, UUID userId);

    Optional<Feedback> findByPublicReference(String publicReference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select feedback from Feedback feedback where feedback.publicReference = :reference")
    Optional<Feedback> findForUpdate(@Param("reference") String reference);

    @Query("""
            select feedback from Feedback feedback
            where feedback.organizationId = :organizationId
              and feedback.userId = :userId
              and (:type is null or feedback.type = :type)
              and (:status is null or feedback.status = :status)
              and (:query is null or
                   lower(feedback.publicReference) like lower(concat('%', :query, '%')) or
                   lower(feedback.title) like lower(concat('%', :query, '%')) or
                   lower(feedback.description) like lower(concat('%', :query, '%')))
            """)
    Page<Feedback> findDeveloperFeedback(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId,
            @Param("type") FeedbackType type,
            @Param("status") FeedbackStatus status,
            @Param("query") String query,
            Pageable pageable);

    @Query("""
            select feedback from Feedback feedback
            where (:organizationId is null or feedback.organizationId = :organizationId)
              and (:userId is null or feedback.userId = :userId)
              and (:projectId is null or feedback.projectId = :projectId)
              and (:type is null or feedback.type = :type)
              and (:status is null or feedback.status = :status)
              and (:priority is null or feedback.priority = :priority)
              and (:team is null or feedback.assignedTeam = :team)
              and (:query is null or
                   lower(feedback.publicReference) like lower(concat('%', :query, '%')) or
                   lower(feedback.title) like lower(concat('%', :query, '%')) or
                   lower(feedback.description) like lower(concat('%', :query, '%')))
            """)
    Page<Feedback> findOperatorFeedback(
            @Param("organizationId") UUID organizationId,
            @Param("userId") UUID userId,
            @Param("projectId") UUID projectId,
            @Param("type") FeedbackType type,
            @Param("status") FeedbackStatus status,
            @Param("priority") FeedbackPriority priority,
            @Param("team") String team,
            @Param("query") String query,
            Pageable pageable);
}
