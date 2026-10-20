package com.perfplatform.persistence.targets;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TargetRegistry.UnsafeTargetException;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.RegisteredTarget;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.domain.target.TargetPolicy;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TargetRegistryStore implements TargetRegistry {

    private final RegisteredTargetJpaRepository targets;
    private final Clock clock;

    public TargetRegistryStore(RegisteredTargetJpaRepository targets, Clock clock) {
        this.targets = targets;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RegisteredTarget register(TeamId teamId, ApplicationId applicationId,
            String applicationName, String environment, TargetClass targetClass,
            TargetAddress address, String actor) {

        TargetPolicy.check(address).ifPresent(refusal -> {
            throw new UnsafeTargetException(refusal.reason());
        });

        String normalizedEnvironment = normalize(environment);
        String normalizedApplication = applicationName.trim();
        var entity = targets.findByTeamIdAndApplicationIdAndEnvironment(
                        teamId.value(), applicationId.value(), normalizedEnvironment)
                .orElseGet(() -> new RegisteredTargetEntity(
                        UUID.randomUUID(), teamId.value(), applicationId.value(),
                        normalizedApplication, normalizedEnvironment, targetClass,
                        address.scheme(), address.host(), address.port(), actor, clock.instant()));
        entity.replace(normalizedApplication, targetClass, address.scheme(), address.host(),
                address.port(), actor, clock.instant());
        return toDomain(targets.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegisteredTarget> find(TeamId teamId, String applicationName,
            String environment) {
        return targets.findByTeamIdAndApplicationNameAndEnvironment(
                        teamId.value(), applicationName.trim(), normalize(environment))
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TargetAddress> resolve(TeamId teamId, String applicationName,
            String environment) {
        return find(teamId, applicationName, environment).map(RegisteredTarget::address);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RegisteredTarget> listForTeam(TeamId teamId) {
        return targets.findByTeamIdOrderByApplicationNameAscEnvironmentAsc(teamId.value()).stream()
                .map(this::toDomain)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
    }

    private RegisteredTarget toDomain(RegisteredTargetEntity entity) {
        return new RegisteredTarget(
                TeamId.of(entity.getTeamId()),
                ApplicationId.of(entity.getApplicationId()),
                entity.getApplicationName(),
                entity.getEnvironment(),
                entity.getTargetClass(),
                new TargetAddress(entity.getScheme(), entity.getHost(), entity.getPort()),
                entity.getCreatedBy(),
                entity.getCreatedAt());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Target environment must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
