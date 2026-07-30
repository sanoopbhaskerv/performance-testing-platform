package com.perfplatform.v2;

import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.ConcurrencyAdmissionPolicy;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.plan.ExecutionPlanCompiler;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.port.TargetDirectory;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.port.TeamProvisioning;
import com.perfplatform.domain.port.TestDefinitionRepository;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.orchestration.OrchestrationSettings;
import com.perfplatform.orchestration.definition.AuthorTestDefinitionUseCase;
import com.perfplatform.domain.target.TargetPolicy;
import com.perfplatform.engine.jmeter.JmeterEngineProvider;
import com.perfplatform.engine.jmeter.LocalJmeterExecutionProvider;
import com.perfplatform.orchestration.execution.FakeExecutionProvider;
import com.perfplatform.orchestration.plan.EngineDispatchingPlanCompiler;
import com.perfplatform.orchestration.plan.EngineRegistry;
import com.perfplatform.results.DefaultRunAnalyzer;
import com.perfplatform.orchestration.run.CancelTestRunUseCase;
import com.perfplatform.orchestration.run.ReadRunReportUseCase;
import com.perfplatform.orchestration.run.ReadTestRunUseCase;
import com.perfplatform.orchestration.run.RunReconciler;
import com.perfplatform.orchestration.run.RunWorkloadService;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase;
import com.perfplatform.persistence.artifact.FileSystemArtifactStore;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
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

    /**
     * The engines this deployment has.
     *
     * <p>Spring supplies every {@link com.perfplatform.engine.spi.ExecutionEngineProvider} bean.
     * Adding an engine is adding a bean; nothing here enumerates them.
     */
    @Bean
    public EngineRegistry engineRegistry(
            List<com.perfplatform.engine.spi.ExecutionEngineProvider> providers) {
        EngineRegistry registry = new EngineRegistry(providers);
        log.info("Execution engines registered: {}", registry.engineIds());
        return registry;
    }

    @Bean
    public com.perfplatform.engine.spi.ExecutionEngineProvider jmeterEngineProvider() {
        return new JmeterEngineProvider();
    }

    /**
     * Plan compilation, dispatched to the engine the specification names.
     *
     * <p>Increment 1C compiled an engine-neutral envelope so the digest and storage contracts could
     * be settled before any engine existed. The engine now produces the plan body, which is what
     * moves compilation behind the SPI (Increment 1D) — and what makes the scenario weights and
     * workload stages V1 discarded survive into the plan.
     */
    @Bean
    public ExecutionPlanCompiler executionPlanCompiler(EngineRegistry engines) {
        return new EngineDispatchingPlanCompiler(engines);
    }

    @Bean
    public RunAnalyzer runAnalyzer() {
        return new DefaultRunAnalyzer();
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
    public ExecutionProvider executionProvider(Clock clock, ArtifactStore artifacts,
            ResultCatalog catalog) {

        String selected = properties.getExecutionProvider();
        boolean production = environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES));

        // Both providers available today execute inside this process or generate nothing, and
        // CLAUDE.md §4 forbids the control plane from generating material load. Neither may run in
        // production, and the refusal is at startup rather than per-run: a platform that reports
        // successful runs for tests that never executed is worse than one that fails to start,
        // because the reports look real.
        if (production) {
            throw new IllegalStateException("Execution provider '" + selected
                    + "' must never run under a production profile: it either generates no load at "
                    + "all, or generates it inside the control-plane process. Production execution "
                    + "runs in isolated Kubernetes Jobs (Increment 1E).");
        }

        if (FakeExecutionProvider.PROVIDER_ID.equalsIgnoreCase(selected)) {
            log.warn("Using the FAKE execution provider: runs progress through their whole "
                    + "lifecycle and produce SYNTHETIC results. No load is generated and the "
                    + "measurements describe nothing. Each run's runner-manifest.json records this.");

            return new FakeExecutionProvider(clock,
                    properties.getFake().getStartupDelay(),
                    properties.getFake().getExecutionDuration(),
                    artifacts, catalog);
        }

        if (LocalJmeterExecutionProvider.PROVIDER_ID.equalsIgnoreCase(selected)) {
            Path jmeter = LocalJmeterExecutionProvider.locateJmeter(properties.getJmeterHome());

            log.warn("Using the LOCAL JMETER execution provider: load is generated by a child "
                    + "process of this JVM, using {}. Development only — production execution runs "
                    + "in isolated Kubernetes Jobs (Increment 1E).", jmeter);

            return new LocalJmeterExecutionProvider(clock, artifacts, catalog, jmeter,
                    Path.of(properties.getWorkDirectory()));
        }

        throw new IllegalStateException("Unsupported execution provider '" + selected
                + "'. Available: " + FakeExecutionProvider.PROVIDER_ID + ", "
                + LocalJmeterExecutionProvider.PROVIDER_ID
                + ". The Kubernetes provider arrives in Increment 1E.");
    }

    /**
     * Artifact storage.
     *
     * <p>Local filesystem, which cannot serve a multi-replica control plane — a file written by one
     * replica is invisible to the others. Object storage arrives with the Kubernetes provider in
     * Increment 1E behind this same port.
     */
    @Bean
    public ArtifactStore artifactStore(Clock clock) {
        return new FileSystemArtifactStore(
                Path.of(properties.getWorkDirectory(), "artifacts"), clock);
    }

    /**
     * Where a run's traffic goes.
     *
     * <p>Resolved from V1's team YAML during the overlap, and checked against {@link TargetPolicy}
     * before it is returned. Non-production only: a production deployment needs a target registry
     * with environment classification and a hostname allowlist (CLAUDE.md §20), and enabling
     * production or unrestricted targets is a pause condition (§31).
     */
    @Bean
    public TargetResolver targetResolver(TeamConfigLoader teamConfigs, TargetDirectory teams) {
        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException(
                    "Targets must not be resolved from V1 team YAML under a production profile. "
                            + "Configure a target registry with environment classification and a "
                            + "hostname allowlist (Increment 4).");
        }
        return new TeamYamlTargetResolver(teamConfigs, teams);
    }

    @Bean
    public RunWorkloadService runWorkloadService(EngineRegistry engines, ArtifactStore artifacts,
            ResultCatalog results, RunAnalyzer analyzer, TargetResolver targets) {
        return new RunWorkloadService(engines, artifacts, results, analyzer, targets);
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

        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
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
    public AuthorTestDefinitionUseCase authorTestDefinitionUseCase(
            TestDefinitionRepository definitions, TeamDirectory teams) {
        return new AuthorTestDefinitionUseCase(definitions, teams);
    }

    /**
     * Mirrors V1's team YAML into the V2 registry at startup.
     *
     * <p>Non-production only, and absent rather than inert under a production profile: a bean that
     * exists but declines to act is one someone later "fixes" by removing the check. Real onboarding
     * arrives with corporate identity in Increment 4.
     */
    @Bean
    @ConditionalOnProperty(prefix = "platform.orchestration", name = "synchronize-teams-from-yaml",
            havingValue = "true", matchIfMissing = true)
    public TeamRegistrySynchronizer teamRegistrySynchronizer(TeamConfigLoader teamConfigs,
            TeamProvisioning provisioning) {

        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException(
                    "Team registry synchronization writes business data from a config file at boot "
                            + "and must never run under a production profile. Onboard teams through "
                            + "the platform's own process (Increment 4), or set "
                            + "platform.orchestration.synchronize-teams-from-yaml=false.");
        }

        log.warn("Team registry synchronization from YAML is enabled: teams and applications "
                + "declared in the team configuration will be created if absent. Development only.");

        return new TeamRegistrySynchronizer(teamConfigs, provisioning);
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
    public ReadTestRunUseCase readTestRunUseCase(TestRunRepository runs, ResultCatalog results,
            AdmissionPolicy policy, CapacityLimits limits) {
        return new ReadTestRunUseCase(runs, results, policy, limits);
    }

    /**
     * Reporting, including serving artifact content.
     *
     * <p>Built on the run reader rather than beside it, so a report and a status read cannot disagree
     * about who may see a run — there is one team check, in one place.
     */
    @Bean
    public ReadRunReportUseCase readRunReportUseCase(ReadTestRunUseCase runs, ResultCatalog results,
            ExecutionPlanRepository plans, DefinitionVersionCatalog versions, ArtifactStore artifacts) {
        return new ReadRunReportUseCase(runs, results, plans, versions, artifacts);
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
            RunWorkloadService workloads,
            Clock clock) {

        String owner = "reconciler-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Reconciler owner identity for this instance: {}", owner);

        return new RunReconciler(runs, shards, plans, versions, compiler, provider,
                admissionPolicy, limits, settings, workloads, clock, owner);
    }
}
