package com.perfplatform.persistence.targets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RegisteredTargetJpaRepository extends JpaRepository<RegisteredTargetEntity, UUID> {

    Optional<RegisteredTargetEntity> findByTeamIdAndApplicationNameAndEnvironment(
            UUID teamId, String applicationName, String environment);

    Optional<RegisteredTargetEntity> findByTeamIdAndApplicationIdAndEnvironment(
            UUID teamId, UUID applicationId, String environment);

    List<RegisteredTargetEntity> findByTeamIdOrderByApplicationNameAscEnvironmentAsc(UUID teamId);
}
