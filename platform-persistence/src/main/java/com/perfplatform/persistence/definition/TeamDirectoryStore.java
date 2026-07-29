package com.perfplatform.persistence.definition;

import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TeamDirectory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Resolves team names against the {@code teams} table. */
@Repository
public class TeamDirectoryStore implements TeamDirectory {

    private final TeamJpaRepository teams;

    public TeamDirectoryStore(TeamJpaRepository teams) {
        this.teams = teams;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamId> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return teams.findByName(name.trim()).map(team -> TeamId.of(team.getId()));
    }
}
