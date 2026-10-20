package com.perfplatform.persistence.definition;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TargetDirectory;
import com.perfplatform.domain.port.TeamDirectory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Resolves team names against the {@code teams} table. */
@Repository
public class TeamDirectoryStore implements TeamDirectory, TargetDirectory {

    private final TeamJpaRepository teams;
    private final ApplicationJpaRepository applications;

    public TeamDirectoryStore(TeamJpaRepository teams, ApplicationJpaRepository applications) {
        this.teams = teams;
        this.applications = applications;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamId> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return teams.findByName(name.trim()).map(team -> TeamId.of(team.getId()));
    }

    /** The reverse lookup, for adapters that bridge to V1 configuration keyed by team name. */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> teamName(TeamId teamId) {
        return teamId == null
                ? Optional.empty()
                : teams.findById(teamId.value()).map(TeamEntity::getName);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApplicationId> findApplication(TeamId teamId, String name) {
        if (teamId == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        return applications.findByTeamIdAndName(teamId.value(), name.trim())
                .map(application -> ApplicationId.of(application.getId()));
    }
}
