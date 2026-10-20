package com.perfplatform.persistence.definition;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TeamProvisioning;
import com.perfplatform.domain.spec.TargetClass;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Registers teams and applications, idempotently.
 *
 * <p>"Ensure" rather than "create": the only caller is a synchronizer that runs on every startup, so
 * the second run must be a no-op. The unique constraints on {@code teams(name)} and
 * {@code applications(team_id, name)} are what make that true under concurrent replicas; the
 * preceding read is an optimization, not the guarantee.
 */
@Repository
public class TeamProvisioningStore implements TeamProvisioning {

    private final TeamJpaRepository teams;
    private final ApplicationJpaRepository applications;
    private final Clock clock;

    public TeamProvisioningStore(TeamJpaRepository teams, ApplicationJpaRepository applications,
            Clock clock) {
        this.teams = teams;
        this.applications = applications;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TeamId ensureTeam(String name) {
        String trimmed = requireName(name, "Team name");
        return teams.findByName(trimmed)
                .map(existing -> TeamId.of(existing.getId()))
                .orElseGet(() -> {
                    TeamId id = TeamId.generate();
                    teams.saveAndFlush(new TeamEntity(id.value(), trimmed, clock.instant()));
                    return id;
                });
    }

    @Override
    @Transactional
    public ApplicationId ensureApplication(TeamId teamId, String name, TargetClass targetClass) {
        String trimmed = requireName(name, "Application name");

        Optional<ApplicationEntity> existing =
                applications.findByTeamIdAndName(teamId.value(), trimmed);
        if (existing.isPresent()) {
            // The stored classification is left alone on purpose. It governs which targets may be
            // tested and whether a run needs approval, so a name lookup must never weaken it.
            return ApplicationId.of(existing.get().getId());
        }

        ApplicationId id = ApplicationId.generate();
        applications.saveAndFlush(new ApplicationEntity(id.value(), teamId.value(), trimmed,
                toEntityClass(targetClass), clock.instant()));
        return id;
    }

    private static ApplicationEntity.TargetClass toEntityClass(TargetClass targetClass) {
        return targetClass == TargetClass.PRODUCTION
                ? ApplicationEntity.TargetClass.PRODUCTION
                : ApplicationEntity.TargetClass.NON_PRODUCTION;
    }

    private static String requireName(String name, String field) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
        return name.trim();
    }
}
