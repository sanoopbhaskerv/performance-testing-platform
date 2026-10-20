package com.perfplatform.orchestration.definition;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.port.TestDefinitionRepository;
import com.perfplatform.domain.port.TestDefinitionRepository.StoredVersion;
import com.perfplatform.domain.port.TestDefinitionRepository.TestDefinitionSummary;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Creates test definitions and appends immutable versions to them.
 *
 * <p>Authoring is where a specification stops being a document and becomes something the platform
 * will act on, so this is where ownership is established: the team and application named in the
 * document are resolved against the registry, the caller is checked against that team, and the
 * result is stored under identifiers rather than names.
 *
 * <p>Names resolve, they never create. A typo in {@code metadata.team} is refused rather than
 * quietly manufacturing a team with no owner, no quota and no approval policy. Onboarding is a
 * separate, deliberate act.
 */
public final class AuthorTestDefinitionUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthorTestDefinitionUseCase.class);

    private final TestDefinitionRepository definitions;
    private final TeamDirectory teams;

    public AuthorTestDefinitionUseCase(TestDefinitionRepository definitions, TeamDirectory teams) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.teams = Objects.requireNonNull(teams, "teams");
    }

    /**
     * A specification ready to be stored.
     *
     * <p>Carries both the parsed model and the text. The model is what ownership and the digest are
     * derived from; the text is what gets stored, verbatim, so a run months later can be reproduced
     * from exactly what was authored rather than from a re-serialization that a library upgrade
     * might render differently.
     *
     * @param specificationJson the submitted document as JSON
     * @param specification     the parsed model
     * @param digest            digest over the canonical form of {@code specification}
     * @param valid             whether validation passed; an invalid version is stored but can
     *                          never be executed
     */
    public record Submission(
            String specificationJson,
            PerformanceTestSpecification specification,
            SpecificationDigest digest,
            boolean valid) {

        public Submission {
            Objects.requireNonNull(specification, "specification");
            Objects.requireNonNull(digest, "digest");
            if (specificationJson == null || specificationJson.isBlank()) {
                throw new IllegalArgumentException("specificationJson must not be null or blank");
            }
        }
    }

    /** A definition together with the version just appended to it. */
    public record AuthoredVersion(TestDefinitionSummary definition, StoredVersion version) {
    }

    /** Raised when the document names a team or application the registry does not know. */
    public static class UnknownOwnerException extends RuntimeException {
        private final transient String field;

        public UnknownOwnerException(String field, String value) {
            super("No " + field + " named '" + value + "' is registered. "
                    + "Check the name, or ask a platform administrator to onboard it.");
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** Raised when a definition identifier does not resolve. */
    public static class UnknownDefinitionException extends RuntimeException {
        public UnknownDefinitionException(TestDefinitionId id) {
            super("No such test definition: " + id);
        }
    }

    /**
     * Creates a definition from a specification and stores it as version 1.
     *
     * <p>The definition's name, team and application all come from the document. Taking them from
     * separate request fields would let a caller store a specification that says one thing under a
     * definition that says another, and the two would drift silently.
     */
    public AuthoredVersion create(Submission submission, CallerContext caller) {
        Owner owner = resolveOwner(submission.specification());
        caller.requireAct(Scope.DEFINITIONS_WRITE, owner.teamId());

        String name = definitionName(submission.specification());
        TestDefinitionSummary definition = definitions.create(
                owner.teamId(), owner.applicationId(), name, caller.subject());

        StoredVersion version = definitions.appendVersion(definition.id(),
                submission.specificationJson(), submission.digest(), submission.valid(),
                caller.subject());

        log.info("Definition created definitionId={} teamId={} name={} version={} valid={} subject={}",
                definition.id(), owner.teamId(), name, version.versionNumber(),
                submission.valid(), caller.subject());

        return new AuthoredVersion(reload(definition.id()), version);
    }

    /**
     * Appends a version to an existing definition.
     *
     * <p>Authorized against the definition's stored team, not the team the document names. A
     * document claiming a different team must not be able to move a definition between owners, and
     * a caller must not be able to append to another team's definition by naming their own.
     */
    public AuthoredVersion appendVersion(TestDefinitionId definitionId, Submission submission,
            CallerContext caller) {

        TestDefinitionSummary definition = definitions.find(definitionId)
                .orElseThrow(() -> new UnknownDefinitionException(definitionId));
        caller.requireAct(Scope.DEFINITIONS_WRITE, definition.teamId());

        StoredVersion version = definitions.appendVersion(definitionId,
                submission.specificationJson(), submission.digest(), submission.valid(),
                caller.subject());

        log.info("Version appended definitionId={} teamId={} version={} valid={} subject={}",
                definitionId, definition.teamId(), version.versionNumber(), submission.valid(),
                caller.subject());

        return new AuthoredVersion(reload(definitionId), version);
    }

    public TestDefinitionSummary get(TestDefinitionId definitionId, CallerContext caller) {
        TestDefinitionSummary definition = definitions.find(definitionId)
                .orElseThrow(() -> new UnknownDefinitionException(definitionId));
        // Authorization failure and non-existence produce the same outcome at the API boundary, so
        // a caller cannot probe which definition identifiers are real.
        caller.requireAct(Scope.DEFINITIONS_READ, definition.teamId());
        return definition;
    }

    /**
     * Every definition the caller's teams own.
     *
     * <p>Scoped to the caller's teams rather than filtered afterwards, for the same reason as the run
     * listing: a query that fetched everything and then removed what the caller may not see is one
     * refactor away from forgetting the second step.
     */
    public List<TestDefinitionSummary> listForCaller(CallerContext caller) {
        List<TestDefinitionSummary> visible = new java.util.ArrayList<>();
        caller.teamIds().stream()
                .filter(teamId -> caller.canAct(Scope.DEFINITIONS_READ, teamId))
                .forEach(teamId -> visible.addAll(definitions.listForTeam(teamId)));
        return List.copyOf(visible);
    }

    public List<StoredVersion> listVersions(TestDefinitionId definitionId, CallerContext caller) {
        get(definitionId, caller);
        return definitions.listVersions(definitionId);
    }

    private TestDefinitionSummary reload(TestDefinitionId id) {
        return definitions.find(id).orElseThrow(() -> new UnknownDefinitionException(id));
    }

    private record Owner(TeamId teamId, ApplicationId applicationId) {
    }

    private Owner resolveOwner(PerformanceTestSpecification specification) {
        String teamName = specification.teamName().orElse(null);
        if (teamName == null || teamName.isBlank()) {
            throw new UnknownOwnerException("team", "<absent>");
        }

        TeamId teamId = teams.findByName(teamName)
                .orElseThrow(() -> new UnknownOwnerException("team", teamName));

        String applicationName = Optional.ofNullable(specification.metadata())
                .map(PerformanceTestSpecification.SpecMetadata::application)
                .orElse(null);
        if (applicationName == null || applicationName.isBlank()) {
            throw new UnknownOwnerException("application", "<absent>");
        }

        ApplicationId applicationId = teams.findApplication(teamId, applicationName)
                .orElseThrow(() -> new UnknownOwnerException("application", applicationName));

        return new Owner(teamId, applicationId);
    }

    private static String definitionName(PerformanceTestSpecification specification) {
        String name = Optional.ofNullable(specification.metadata())
                .map(PerformanceTestSpecification.SpecMetadata::name)
                .orElse(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("The specification must declare metadata.name");
        }
        return name;
    }
}
