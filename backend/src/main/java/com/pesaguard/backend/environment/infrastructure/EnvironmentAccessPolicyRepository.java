package com.pesaguard.backend.environment.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pesaguard.backend.environment.domain.EnvironmentAccessPolicy;
import com.pesaguard.backend.environment.domain.EnvironmentAccessSubjectType;

public interface EnvironmentAccessPolicyRepository extends JpaRepository<EnvironmentAccessPolicy, UUID> {

    List<EnvironmentAccessPolicy> findByEnvironmentIdOrderBySubjectTypeAscSubjectRoleAsc(UUID environmentId);

    List<EnvironmentAccessPolicy> findByEnvironmentIdAndSubjectTypeAndSubjectRole(
            UUID environmentId, EnvironmentAccessSubjectType subjectType, String subjectRole);

    void deleteByEnvironmentIdAndSubjectTypeAndSubjectRole(
            UUID environmentId, EnvironmentAccessSubjectType subjectType, String subjectRole);
}