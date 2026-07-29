package com.perfplatform.orchestration.support;

import com.perfplatform.domain.identity.ExecutionPlanId;
import com.perfplatform.domain.identity.RunnerShardId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.identity.TestDefinitionId;
import com.perfplatform.domain.identity.TestDefinitionVersionId;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.plan.ExecutionPlan;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.ExecutionPlanRepository.ImmutablePlanException;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.run.RunnerShard;
import com.perfplatform.domain.security.CallerContext;
import com.perfplatform.domain.security.Scope;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SpecificationDigest;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fixtures and in-memory port implementations for orchestration tests.
 *
 * <p>Built here rather than shared from the domain's test sources so that orchestration's tests do
 * not depend on another module's test artifacts — a dependency that makes both harder to change.
 */
public final class OrchestrationFixtures {

    public static final String ENVIRONMENT = "performance";

    private OrchestrationFixtures() {
    }

    /** A protocol-only specification with two weighted scenarios and a staged model. */
    public static PerformanceTestSpecification apiSpecification() {
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("proposal-baseline", "advice", "proposal", Map.of()),
                new TestSpec(
                        new EnvironmentSpec(ENVIRONMENT, "advice-perf", TargetClass.NON_PRODUCTION),
                        List.of(), List.of(), List.of(),
                        List.of(new WorkloadSpec(
                                "api-load", WorkloadType.PROTOCOL, "jmeter", null,
                                new LoadModel(LoadModelType.STAGES, List.of(
                                        new LoadStage(Duration.ofMinutes(5), 100),
                                        new LoadStage(Duration.ofMinutes(10), 200))),
                                List.of(
                                        new ScenarioSpec("search", 70, "searchClient"),
                                        new ScenarioSpec("open", 30, "getProposal")),
                                null, null, null)),
                        List.of(), null, null));
    }

    /** A caller who may do everything for the given team, and nothing for any other. */
    public static CallerContext caller(TeamId teamId) {
        return new CallerContext("alice@example.com", Set.of(teamId),
                Set.of(Scope.RUNS_EXECUTE, Scope.RUNS_CANCEL, Scope.RUNS_READ), false);
    }

    /** A catalog holding versions supplied by the test. */
    public static final class StubDefinitionVersionCatalog implements DefinitionVersionCatalog {

        private final Map<TestDefinitionVersionId, DefinitionVersion> versions = new HashMap<>();

        /** Registers an executable version owned by the given team. */
        public DefinitionVersion add(TeamId teamId) {
            return add(teamId, true);
        }

        public DefinitionVersion add(TeamId teamId, boolean executable) {
            PerformanceTestSpecification specification = apiSpecification();
            DefinitionVersion version = new DefinitionVersion(
                    TestDefinitionVersionId.generate(),
                    TestDefinitionId.generate(),
                    teamId,
                    1,
                    SpecificationDigest.of(specification),
                    specification,
                    executable);
            versions.put(version.id(), version);
            return version;
        }

        /** Forgets a version, simulating one that became unreadable between passes. */
        public void remove(TestDefinitionVersionId id) {
            versions.remove(id);
        }

        @Override
        public Optional<DefinitionVersion> find(TestDefinitionVersionId id) {
            return Optional.ofNullable(versions.get(id));
        }
    }

    /** An in-memory shard repository. */
    public static final class InMemoryShardRepository implements RunnerShardRepository {

        private final Map<RunnerShardId, RunnerShard> shards = new HashMap<>();

        @Override
        public Optional<RunnerShard> find(RunnerShardId id) {
            return Optional.ofNullable(shards.get(id));
        }

        @Override
        public List<RunnerShard> findByRun(TestRunId runId) {
            List<RunnerShard> found = new ArrayList<>(shards.values().stream()
                    .filter(shard -> shard.runId().equals(runId))
                    .toList());
            found.sort((left, right) -> Integer.compare(left.shardIndex(), right.shardIndex()));
            return found;
        }

        @Override
        public RunnerShard save(RunnerShard shard) {
            shards.put(shard.id(), shard);
            return shard;
        }
    }

    /** An in-memory plan repository that refuses to overwrite a stored plan. */
    public static final class InMemoryPlanRepository implements ExecutionPlanRepository {

        private final Map<ExecutionPlanId, ExecutionPlan> plans = new HashMap<>();

        @Override
        public Optional<ExecutionPlan> find(ExecutionPlanId id) {
            return Optional.ofNullable(plans.get(id));
        }

        @Override
        public ExecutionPlan save(ExecutionPlan plan) {
            if (plans.containsKey(plan.id())) {
                throw new ImmutablePlanException(plan.id());
            }
            plans.put(plan.id(), plan);
            return plan;
        }

        public int count() {
            return plans.size();
        }
    }
}
