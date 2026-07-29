package com.perfplatform.v2;

import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.ConcurrencyAdmissionPolicy;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.plan.BaselineExecutionPlanCompiler;
import com.perfplatform.domain.plan.ExecutionPlanCompiler;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.orchestration.OrchestrationSettings;
import com.perfplatform.orchestration.execution.FakeExecutionProvider;
import com.perfplatform.orchestration.run.CancelTestRunUseCase;
import com.perfplatform.orchestration.run.ReadTestRunUseCase;
import com.perfplatform.orchestration.run.RunReconciler;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wires durable run orchestration.
 *
 * <p>The composition root, and the only place that knows about every module at once. The use cases,
 * the reconciler and the admission policy are plain classes with constructor injection and no
 * stereotype annotations, so they can be unit-tested without Spring — the same pattern
 * {@code V2ApiConfiguration} established for the specification parser.
 */
@Configuration
@EnableConfigurationProperties(OrchestrationProperties.class)
public class OrchestrationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationConfiguration.class);

    /** Profiles under which a non-executing provider or development identity is unacceptable. */
    private static final String[] PRODUCTION_PROFILES = {"production", "prod", "shared-runner"};

    private final OrchestrationProperties properties;
    private final Environment environment;

    public OrchestrationConfiguration(OrchestrationProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Bean
    public OrchestrationSettings orchestrationSettings() {
        return new OrchestrationSettings(
                properties.getLeaseDuration(),
                properties.getHeartbeatTolerance(),
                properties.getExecutionGrace(),
                properties.getClaimBatchSize(),
                properties.getValidationGrace());
    }

    /**
     * Resolves configured team ceilings to identifiers at startup.
     *
     * <p>Overrides are configured by team name because that is what a person editing a values file
     * knows; they are resolved once, here, so that admission control works in identifiers and never
     * has to look a name up while holding the claim mutex.
     *
     * <p>An override naming a team that does not exist is logged and dropped rather than failing
     * startup. The consequence of dropping it is that the team gets the default ceiling, which is
     * the safe direction — failing startup over a stale entry would take the platform down for
     * everyone because one team was renamed.
     */
    @Bean
    public CapacityLimits capacityLimits(TeamDirectory teams) {
        Map<TeamId, Integer> overrides = new HashMap<>();

        properties.getCapacity().getTeamOverrides().forEach((teamName, limit) ->
                teams.findByName(teamName).ifPresentOrElse(
                        teamId -> overrides.put(teamId, limit),
                        () -> log.warn("Capacity override for unknown team '{}' ignored; "
                                + "it will use the default limit of {}",
                                teamName, properties.getCapacity().getDefaultTeamMaxRuns())));

        CapacityLimits limits = new CapacityLimits(
                properties.getCapacity().getGlobalMaxRuns(),
                properties.getCapacity().getDefaultTeamMaxRuns(),
                overrides);

        log.info("Run capacity limits: global={} defaultPerTeam={} overrides={}",
                limits.globalMaxRuns(), limits.defaultTeamMaxRuns(), overrides.size());
        return limits;
    }

    @Bean
    public AdmissionPolicy admissionPolicy() {
        return new ConcurrencyAdmissionPolicy();
    }

    @Bean
    public ExecutionPlanCompiler executionPlanCompiler() {
        return new BaselineExecutionPlanCompiler();
    }

    /**
     * The execution provider.
     *
     * <p>Refuses to supply the fake provider under a production profile. A platform that reports
     * successful runs while generating no load is worse than one that fails to start: the reports
     * look real, and a team would act on numbers that describe nothing. The Kubernetes provider is
     * Increment 1E, and until it exists this is the honest failure.
     */
    @Bean
    public ExecutionProvider executionProvider(Clock clock) {
        String selected = properties.getExecutionProvider();

        if (!FakeExecutionProvider.PROVIDER_ID.equalsIgnoreCase(selected)) {
            throw new IllegalStateException("Unsupported execution provider '" + selected
                    + "'. Only '" + FakeExecutionProvider.PROVIDER_ID
                    + "' is implemented; the Kubernetes provider arrives in Increment 1E.");
        }

        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException(
                    "The 'fake' execution provider generates no load and must never run under a "
                            + "production profile. It would report successful runs for tests that "
                            + "never executed. Configure a real execution provider "
                            + "(available from Increment 1E).");
        }

        log.warn("Using the FAKE execution provider: runs will progress through their lifecycle "
                + "but no load is generated. This is for development and testing only.");

        return new FakeExecutionProvider(clock,
                properties.getFake().getStartupDelay(),
                properties.getFake().getExecutionDuration());
    }

    @Bean
    public DefinitionVersionCatalog definitionVersionCatalog(TestDefinitionRegistry registry,
            SpecificationParser parser) {
        return new PersistedDefinitionVersionCatalog(registry, parser);
    }

    /**
     * Caller identity.
     *
     * <p>Development-only, and refused under a production profile for the same reason as the fake
     * provider: an authentication scheme nobody chose must not become the one production uses by
     * default. Corporate OIDC is Increment 4 and a pause condition (CLAUDE.md §31).
     */
    @Bean
    public CallerContextProvider callerContextProvider(TeamConfigLoader teamConfigs,
            TeamDirectory teams) {

        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException(
                    "The development caller-identity provider derives identity from V1 API keys and "
                            + "must never run under a production profile. Configure corporate OIDC "
                            + "and scoped service identities (Increment 4).");
        }

        log.warn("Using the DEVELOPMENT caller-identity provider: V2 callers are identified by their "
                + "V1 API key and scoped to the team that owns it. Not for production.");

        return new DevelopmentCallerContextProvider(teamConfigs, teams);
    }

    @Bean
    public TriggerTestRunUseCase triggerTestRunUseCase(TestRunRepository runs,
            DefinitionVersionCatalog versions, Clock clock) {
        return new TriggerTestRunUseCase(runs, versions, clock);
    }

    @Bean
    public CancelTestRunUseCase cancelTestRunUseCase(TestRunRepository runs, Clock clock) {
        return new CancelTestRunUseCase(runs, clock);
    }

    @Bean
    public ReadTestRunUseCase readTestRunUseCase(TestRunRepository runs, AdmissionPolicy policy,
            CapacityLimits limits) {
        return new ReadTestRunUseCase(runs, policy, limits);
    }

    /**
     * The reconciler for this replica.
     *
     * <p>Its owner identity must be unique per process, since it is what distinguishes this
     * instance's lease from a peer's. A random value per start is correct: a restarted process is a
     * new owner, and the runs its predecessor held become claimable when their leases lapse — which
     * is exactly the recovery behavior wanted.
     */
    @Bean
    public RunReconciler runReconciler(TestRunRepository runs,
            RunnerShardRepository shards,
            ExecutionPlanRepository plans,
            DefinitionVersionCatalog versions,
            ExecutionPlanCompiler compiler,
            ExecutionProvider provider,
            AdmissionPolicy admissionPolicy,
            CapacityLimits limits,
            OrchestrationSettings settings,
            Clock clock) {

        String owner = "reconciler-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Reconciler owner identity for this instance: {}", owner);

        return new RunReconciler(runs, shards, plans, versions, compiler, provider,
                admissionPolicy, limits, settings, clock, owner);
    }
}
