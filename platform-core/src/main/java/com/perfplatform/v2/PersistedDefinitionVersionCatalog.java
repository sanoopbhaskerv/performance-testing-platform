package com.perfplatform.v2;

import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.persistence.definition.TestDefinitionEntity;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import com.perfplatform.persistence.definition.TestDefinitionVersionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Serves the definition-version catalog by combining storage with the parser.
 *
 * <p>This lives in the composition root because it is the only place allowed to know both halves.
 * {@code platform-orchestration} needs a version's team and its parsed specification, but must not
 * depend on persistence entities or on the API module's parser — the module rules exist so that
 * orchestration stays testable without a database and unaffected by how specifications are stored.
 * Core depends on everything, so it is where the two are joined.
 *
 * <h2>Parsing on read</h2>
 * The stored specification is parsed each time a version is resolved rather than cached. Run
 * triggers and reconciliation passes are measured in single digits per hour, so the cost is
 * irrelevant, and a cache would introduce the one bug this model is built to avoid: two components
 * disagreeing about what an immutable version says.
 */
public class PersistedDefinitionVersionCatalog implements DefinitionVersionCatalog {

    private static final Logger log = LoggerFactory.getLogger(PersistedDefinitionVersionCatalog.class);

    private final TestDefinitionRegistry registry;
    private final SpecificationParser parser;

    public PersistedDefinitionVersionCatalog(TestDefinitionRegistry registry,
            SpecificationParser parser) {
        this.registry = registry;
        this.parser = parser;
    }

    @Override
    public Optional<DefinitionVersion> find(TestDefinitionVersionId id) {
        Optional<TestDefinitionVersionEntity> stored = registry.findVersionById(id);
        if (stored.isEmpty()) {
            return Optional.empty();
        }

        TestDefinitionVersionEntity version = stored.get();
        Optional<TestDefinitionEntity> definition =
                registry.findDefinition(TestDefinitionId.of(version.getDefinitionId()));
        if (definition.isEmpty()) {
            // A version whose definition is missing cannot be attributed to a team, and a run with
            // no team has no quota, no authorization and no owner. Refused rather than guessed.
            log.error("Version {} references definition {} which does not exist",
                    id, version.getDefinitionId());
            return Optional.empty();
        }

        Optional<PerformanceTestSpecification> specification =
                parser.parse(version.getSpecificationJson()).specification();
        if (specification.isEmpty()) {
            log.error("Stored specification for version {} could not be parsed", id);
            return Optional.empty();
        }

        return Optional.of(new DefinitionVersion(
                TestDefinitionVersionId.of(version.getId()),
                TestDefinitionId.of(version.getDefinitionId()),
                TeamId.of(definition.get().getTeamId()),
                version.getVersionNumber(),
                SpecificationDigest.parse(version.getSpecificationDigest()),
                specification.get(),
                isExecutable(version)));
    }

    /**
     * Whether this version may reach a runner.
     *
     * <p>Both conditions. A version that failed validation describes a test the platform could not
     * make sense of; a version awaiting or refused approval describes one nobody has agreed to run.
     * Either alone is enough to stop it.
     */
    private static boolean isExecutable(TestDefinitionVersionEntity version) {
        boolean valid = version.getValidationStatus()
                == TestDefinitionVersionEntity.ValidationStatus.VALID;
        boolean approved = version.getApprovalStatus()
                == TestDefinitionVersionEntity.ApprovalStatus.NOT_REQUIRED
                || version.getApprovalStatus()
                == TestDefinitionVersionEntity.ApprovalStatus.APPROVED;
        return valid && approved;
    }
}
