package com.perfplatform.v2;

import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.api.v2.spec.SpecificationParser;
import com.perfplatform.api.v2.validator.SpecificationDryRunService;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.domain.admission.AdmissionPolicy;
import com.perfplatform.domain.admission.CapacityLimits;
import com.perfplatform.domain.admission.ConcurrencyAdmissionPolicy;
import com.perfplatform.domain.execution.ExecutionProvider;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.importer.SourceImporter;
import com.perfplatform.domain.plan.ExecutionPlanCompiler;
import com.perfplatform.domain.port.ArtifactStore;
import com.perfplatform.domain.port.DatasetCatalog;
import com.perfplatform.domain.port.DatasetStore;
import com.perfplatform.domain.port.HostAddressResolver;
import com.perfplatform.domain.port.RecorderAuditLog;
import com.perfplatform.domain.port.RecorderBrowser;
import com.perfplatform.domain.port.ResultCatalog;
import com.perfplatform.domain.port.ProgressCatalog;
import com.perfplatform.domain.port.RunAnalyzer;
import com.perfplatform.domain.port.SecretResolver;
import com.perfplatform.domain.port.BrowserSourceStore;
import com.perfplatform.persistence.source.FileSystemBrowserSourceStore;
import com.perfplatform.persistence.source.S3BrowserSourceStore;
import com.perfplatform.domain.port.SessionAcquisitionProvider;
import com.perfplatform.domain.port.SessionPoolProvider;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.port.DefinitionVersionCatalog;
import com.perfplatform.domain.port.ExecutionPlanRepository;
import com.perfplatform.domain.port.RunnerShardRepository;
import com.perfplatform.domain.port.RuntimeCredentialStore;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.port.TeamProvisioning;
import com.perfplatform.domain.port.TestDefinitionRepository;
import com.perfplatform.domain.port.TestRunRepository;
import com.perfplatform.orchestration.OrchestrationSettings;
import com.perfplatform.orchestration.dataset.ManageDatasetsUseCase;
import com.perfplatform.orchestration.definition.AuthorTestDefinitionUseCase;
import com.perfplatform.orchestration.target.ManageTargetsUseCase;
import com.perfplatform.engine.jmeter.JmeterEngineProvider;
import com.perfplatform.engine.jmeter.LocalJmeterExecutionProvider;
import com.perfplatform.engine.playwright.LocalPlaywrightExecutionProvider;
import com.perfplatform.engine.playwright.PlaywrightEngineProvider;
import com.perfplatform.orchestration.execution.EngineRoutingExecutionProvider;
import com.perfplatform.orchestration.execution.FakeExecutionProvider;
import com.perfplatform.orchestration.plan.EngineDispatchingPlanCompiler;
import com.perfplatform.orchestration.plan.EngineRegistry;
import com.perfplatform.domain.recorder.RecorderAddressAllowances;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.recorder.RecorderViewport;
import com.perfplatform.orchestration.recorder.ManageRecordingsUseCase;
import com.perfplatform.orchestration.recorder.RecorderBridgeSettings;
import com.perfplatform.orchestration.recorder.RecorderSessionRegistry;
import com.perfplatform.orchestration.recorder.UnavailableRecorderBrowser;
import com.perfplatform.runner.chrome.ChromeRecorderSettings;
import com.perfplatform.runner.chrome.LocalChromeRecorderBrowser;
import com.perfplatform.results.DefaultRunAnalyzer;
import com.perfplatform.orchestration.run.CancelTestRunUseCase;
import com.perfplatform.orchestration.run.ExternalSessionBrokerSessionAcquisitionProvider;
import com.perfplatform.orchestration.run.InMemoryRuntimeCredentialStore;
import com.perfplatform.orchestration.run.MountedFileSecretResolver;
import com.perfplatform.orchestration.run.ReadRunProgressUseCase;
import com.perfplatform.orchestration.run.ReadRunReportUseCase;
import com.perfplatform.orchestration.run.ReadTestRunUseCase;
import com.perfplatform.orchestration.run.RuntimeCredentialSessionAcquisitionProvider;
import com.perfplatform.orchestration.run.RuntimeCredentialSessionPoolProvider;
import com.perfplatform.orchestration.run.RunReconciler;
import com.perfplatform.orchestration.run.RunWorkloadService;
import com.perfplatform.orchestration.run.TriggerTestRunUseCase;
import com.perfplatform.persistence.artifact.FileSystemArtifactStore;
import com.perfplatform.persistence.artifact.S3ArtifactStore;
import com.perfplatform.persistence.dataset.FileSystemDatasetStore;
import com.perfplatform.persistence.dataset.S3DatasetStore;
import com.perfplatform.persistence.definition.TestDefinitionRegistry;
import com.perfplatform.runner.kubernetes.EphemeralSecretCleaner;
import com.perfplatform.runner.kubernetes.KubernetesEphemeralSecretCleaner;
import com.perfplatform.runner.kubernetes.KubernetesExecutionProvider;
import com.perfplatform.runner.kubernetes.RunnerCellSettings;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    @Bean
    public RecorderSecuritySettings recorderSecuritySettings() {
        boolean enabled = properties.getRecorder().getNetworkEnforcement().isEnabled();
        if (!enabled && environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException("Recorder network enforcement must not be disabled "
                    + "under a production profile. A recording browser is a user-driven network "
                    + "client inside the runner network; set "
                    + "platform.orchestration.recorder.network-enforcement.enabled=true or "
                    + "move this test to a non-production profile.");
        }
        if (!enabled) {
            log.warn("Recorder network enforcement is DISABLED. This is local testing only: "
                    + "browser route guards may still block known destinations, but Kubernetes "
                    + "will not enforce recorder egress.");
        }
        return new RecorderSecuritySettings(enabled);
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
    public com.perfplatform.domain.admission.ExecutionLimits executionLimits() {
        return new com.perfplatform.domain.admission.ExecutionLimits(
                properties.getCapacity().getMaxProtocolUsersPerRun(), properties.getCapacity().getMaxDuration());
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
     * The repositories a Playwright test may be imported from.
     *
     * <p>Logged at startup either way, because both states are worth knowing about and neither is
     * self-evident from the outside. An empty allowlist is not a misconfiguration to be fixed
     * silently — a deployment that does not import Playwright tests from git is a perfectly ordinary
     * deployment — but it is the difference between "no test named a repository" and "every test
     * naming one was refused", and an operator reading logs should not have to guess which.
     */
    @Bean
    public com.perfplatform.domain.spec.GitSourceAllowlist gitSourceAllowlist() {
        var allowlist = com.perfplatform.domain.spec.GitSourceAllowlist.of(
                properties.getPlaywright().getAllowedRepositories());
        if (allowlist.isConfigured()) {
            log.info("Playwright git-pinned imports are permitted from {} repositories: {}",
                    allowlist.repositories().size(), allowlist.repositories());
        } else {
            log.info("Playwright git-pinned imports are DISABLED: no repositories are allowlisted. "
                    + "Set platform.orchestration.playwright.allowed-repositories to permit them.");
        }
        return allowlist;
    }

    @Bean
    public com.perfplatform.engine.spi.ExecutionEngineProvider playwrightEngineProvider() {
        return new PlaywrightEngineProvider();
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

    /**
     * The cURL importer.
     *
     * <p>A bean per importer, discovered by the API through the {@code SourceImporter} port. Nothing
     * enumerates them, so adding an importer is adding a bean — and an importer that is not deployed
     * is refused by name rather than silently returning nothing.
     */
    @Bean
    public com.perfplatform.domain.importer.SourceImporter curlImporter() {
        return new com.perfplatform.importer.curl.CurlImporter();
    }

    /**
     * The OpenAPI / Swagger importer.
     *
     * <p>Registered exactly as the cURL one is. It holds Jackson, which the domain deliberately does
     * not: an importer is an adapter, and reading a published document is the whole of its job.
     */
    @Bean
    public com.perfplatform.domain.importer.SourceImporter openApiImporter() {
        return new com.perfplatform.importer.openapi.OpenApiImporter();
    }

    /**
     * The HAR importer.
     *
     * <p>The one whose findings matter most. A capture is a recording of a live session, so what it
     * <em>refuses</em> — cookies, tokens, passwords, third-party hosts — is the greater part of what
     * it does.
     */
    @Bean
    public com.perfplatform.domain.importer.SourceImporter harImporter() {
        return new com.perfplatform.importer.har.HarImporter();
    }

    /**
     * The Postman importer.
     *
     * <p>The only source that carries executable code. It is flagged and never evaluated
     * (CLAUDE.md §23): the control plane does not run code that arrived in a document.
     */
    @Bean
    public com.perfplatform.domain.importer.SourceImporter postmanImporter() {
        return new com.perfplatform.importer.postman.PostmanImporter();
    }

    @Bean
    public RunAnalyzer runAnalyzer() {
        return new DefaultRunAnalyzer();
    }

    /**
     * The execution provider.
     *
     * <p>Only the Kubernetes provider may run in production. The other two either generate no load
     * at all or generate it inside this JVM, and CLAUDE.md §4 forbids the control plane from
     * producing material load. The refusal is at startup rather than per-run: a platform that
     * reports successful runs for tests that never executed is worse than one that fails to start,
     * because the reports look real.
     */
    @Bean
    public ExecutionProvider executionProvider(Clock clock, ArtifactStore artifacts,
            ResultCatalog catalog) {

        String selected = properties.getExecutionProvider();
        boolean production = environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES));

        if (KubernetesExecutionProvider.PROVIDER_ID.equalsIgnoreCase(selected)) {
            return new KubernetesExecutionProvider(kubernetesClient(), runnerCellSettings());
        }

        if (production) {
            throw new IllegalStateException("Execution provider '" + selected
                    + "' must never run under a production profile: it either generates no load at "
                    + "all, or generates it inside the control-plane process. Set "
                    + "platform.orchestration.execution-provider=kubernetes.");
        }

        if (FakeExecutionProvider.PROVIDER_ID.equalsIgnoreCase(selected)) {
            log.warn("Using the FAKE execution provider: runs progress through their whole "
                    + "lifecycle and produce SYNTHETIC results. No load is generated, the "
                    + "measurements describe nothing, and every run is marked synthetic and fails "
                    + "with NO_LOAD_GENERATED rather than passing.");

            return new FakeExecutionProvider(clock,
                    properties.getFake().getStartupDelay(),
                    properties.getFake().getExecutionDuration(),
                    artifacts, catalog);
        }

        if (LocalJmeterExecutionProvider.PROVIDER_ID.equalsIgnoreCase(selected)) {
            Path jmeter = LocalJmeterExecutionProvider.locateJmeter(properties.getJmeterHome());
            // Refused at startup rather than discovered mid-run. JMeter skips thread groups it
            // cannot resolve and carries on, so a missing plugin presents as a successful run that
            // generated no load — which is the one outcome this platform must never produce.
            LocalJmeterExecutionProvider.requireCustomThreadGroups(jmeter);

            log.warn("Using the LOCAL JMETER execution provider: load is generated by a child "
                    + "process of this JVM, using {}. Development only — production execution runs "
                    + "in isolated Kubernetes Jobs.", jmeter);

            ExecutionProvider jmeterProvider = new LocalJmeterExecutionProvider(
                    clock, artifacts, catalog, jmeter, Path.of(properties.getWorkDirectory()));

            return routeByEngine(jmeterProvider, clock, artifacts, catalog);
        }

        throw new IllegalStateException("Unsupported execution provider '" + selected
                + "'. Available: " + FakeExecutionProvider.PROVIDER_ID + ", "
                + LocalJmeterExecutionProvider.PROVIDER_ID + ", "
                + KubernetesExecutionProvider.PROVIDER_ID + ".");
    }

    /**
     * Pairs the local JMeter adapter with a local browser adapter, when one is configured.
     *
     * <p>Which adapter runs a shard is a property of the shard's engine, not of the deployment. Both
     * local adapters exist for the same development loop, and the alternative to routing between
     * them is what this replaces: a browser workload reaching the JMeter adapter and being refused,
     * having already been validated, admitted and given a queue slot.
     *
     * <p>The browser adapter is added only when a workspace root is configured, and its absence is
     * not an error. A deployment doing protocol work needs no browser runner, and demanding a Node
     * install from everyone who runs JMeter locally would be a worse default than a clear refusal at
     * the point somebody actually triggers a browser run.
     */
    private ExecutionProvider routeByEngine(ExecutionProvider jmeterProvider, Clock clock,
            ArtifactStore artifacts, ResultCatalog catalog) {

        String workspaceRoot = properties.getPlaywright().getWorkspaceRoot();
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            log.info("No Playwright workspace root is configured, so browser workloads have no "
                    + "local execution provider. Set "
                    + "platform.orchestration.playwright.workspace-root to run them from here.");
            return jmeterProvider;
        }

        Path root = LocalPlaywrightExecutionProvider.requireWorkspaceRoot(workspaceRoot);
        Path node = LocalPlaywrightExecutionProvider.locateNodeCommand(
                properties.getPlaywright().getNodeCommand(), "node");
        Path npx = LocalPlaywrightExecutionProvider.locateNodeCommand(
                properties.getPlaywright().getNpxCommand(), "npx");

        log.warn("Using the LOCAL PLAYWRIGHT execution provider for browser workloads: real "
                        + "browsers are started as child processes of this JVM, from {}. "
                        + "Development only — production browser execution runs in isolated "
                        + "Kubernetes Jobs using the pinned runner image.", root);

        ExecutionProvider playwrightProvider = new LocalPlaywrightExecutionProvider(
                clock, artifacts, catalog, node, npx, root, Path.of(properties.getWorkDirectory()), browserSourceStore());

        return new EngineRoutingExecutionProvider(Map.of(
                JmeterEngineProvider.ENGINE_ID, jmeterProvider,
                PlaywrightEngineProvider.ENGINE_ID, playwrightProvider));
    }

    /**
     * The production provider: workloads run as isolated, ephemeral Kubernetes Jobs.
     *
     * <p>Configuration is validated here rather than at the first run. A runner cell missing its
     * namespace, service account or image allowlist would otherwise fail minutes after someone
     * triggered a test, having already consumed a queue slot — and would read as a platform fault
     * rather than the deployment mistake it is.
     *
     * <p>A floating image tag is refused for a subtler reason: it means two runs of the same
     * immutable definition version can execute on different runner builds. The plan digest would
     * still match while the thing executing it had changed, which quietly breaks the promise that a
     * run is reproducible (CLAUDE.md §8).
     */
    @Bean
    @ConditionalOnProperty(prefix = "platform.orchestration", name = "execution-provider",
            havingValue = "kubernetes")
    public RunnerCellSettings runnerCellSettings() {
        OrchestrationProperties.RunnerCell cell = properties.getRunnerCell();

        requireConfigured(cell.getNamespace(), "platform.orchestration.runner-cell.namespace",
                "the namespace runner Jobs are created in");
        requireConfigured(cell.getServiceAccount(),
                "platform.orchestration.runner-cell.service-account",
                "the identity runner Pods run as, which must not be the control plane's");
        if (cell.getImages().isEmpty()) {
            throw new IllegalStateException(
                    "No runner images are configured. Set platform.orchestration.runner-cell.images "
                            + "as an allowlist of engine to pinned image; an engine with no entry "
                            + "cannot be provisioned at all.");
        }

        // A runner fetches its plan and uploads its results over the network — it shares no
        // filesystem with the control plane. Without object storage it has a key and nowhere to use
        // it, so this is refused here rather than discovered by a Job that starts and cannot work.
        OrchestrationProperties.Artifacts artifactSettings = properties.getArtifacts();
        if (!"s3".equalsIgnoreCase(artifactSettings.getStore())) {
            throw new IllegalStateException(
                    "The Kubernetes execution provider requires object storage: a runner Pod cannot "
                            + "reach the control plane's filesystem. Set "
                            + "platform.orchestration.artifacts.store=s3.");
        }
        requireConfigured(artifactSettings.getBucket(), "platform.orchestration.artifacts.bucket",
                "the bucket runners read plans from and write results to");

        RunnerCellSettings settings = new RunnerCellSettings(
                cell.getNamespace(),
                cell.getServiceAccount(),
                cell.getImages(),
                cell.getCpuRequest(), cell.getCpuLimit(),
                cell.getMemoryRequest(), cell.getMemoryLimit(),
                cell.getWorkspaceSizeLimit(),
                cell.getFinishedJobTtl(),
                cell.getDeadlineGrace(),
                new RunnerCellSettings.ArtifactStorage(
                        artifactSettings.getBucket(),
                        artifactSettings.getRegion(),
                        // Usually identical to the control plane's, and different when storage is
                        // reached by another route from inside the cluster.
                        artifactSettings.effectiveRunnerEndpoint(),
                        artifactSettings.isPathStyleAccess(),
                        artifactSettings.getRunnerCredentialsSecret()));

        if (!settings.hasOnlyPinnedImages()) {
            throw new IllegalStateException("Runner images must be pinned, but these are not: "
                    + settings.floatingImages() + ". A moving tag means two runs of the same "
                    + "immutable definition version can execute on different runner builds.");
        }

        log.info("Using the KUBERNETES execution provider: workloads run as isolated, ephemeral "
                        + "Jobs in namespace={} as serviceAccount={} with engines={}",
                settings.namespace(), settings.serviceAccount(), settings.runnerImages().keySet());

        return settings;
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.orchestration", name = "execution-provider",
            havingValue = "kubernetes")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.orchestration", name = "execution-provider",
            havingValue = "kubernetes")
    public EphemeralSecretCleaner kubernetesEphemeralSecretCleaner(
            KubernetesClient client, RunnerCellSettings settings) {
        return new KubernetesEphemeralSecretCleaner(client, settings);
    }

    /**
     * Artifact storage.
     *
     * <p>The filesystem store cannot serve a multi-replica control plane — a file written by one
     * replica is invisible to the others — and a runner Pod on another node cannot reach it at all.
     * It is therefore refused in production, where S3-compatible object storage is required.
     */
    @Bean
    public ArtifactStore artifactStore(Clock clock) {
        OrchestrationProperties.Artifacts settings = properties.getArtifacts();
        boolean production = environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES));

        if ("s3".equalsIgnoreCase(settings.getStore())) {
            requireConfigured(settings.getBucket(), "platform.orchestration.artifacts.bucket",
                    "the bucket artifacts are stored in");
            return new S3ArtifactStore(s3Client(settings), settings.getBucket(), clock);
        }

        if (production) {
            throw new IllegalStateException(
                    "Artifacts must not be stored on a pod-local filesystem in production: a file "
                            + "written by one replica is invisible to the others, and a runner "
                            + "cannot reach it at all. Set "
                            + "platform.orchestration.artifacts.store=s3.");
        }

        return new FileSystemArtifactStore(
                Path.of(properties.getWorkDirectory(), "artifacts"), clock);
    }

    /**
     * Dataset storage.
     *
     * <p>Follows the artifact store's choice rather than having a switch of its own. A deployment
     * whose artifacts are in object storage and whose datasets are on a pod-local disk would fail in
     * the least obvious way available: runs would provision, and every shard would start without the
     * data it was supposed to send.
     */
    @Bean
    public BrowserSourceStore browserSourceStore() {
        var settings = properties.getArtifacts();
        if ("s3".equalsIgnoreCase(settings.getStore())) {
            return new S3BrowserSourceStore(s3Client(settings), settings.getBucket());
        }
        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException("Browser sources require object storage in production.");
        }
        return new FileSystemBrowserSourceStore(Path.of(properties.getWorkDirectory(), "sources"));
    }

    @Bean
    public DatasetStore datasetStore() {
        OrchestrationProperties.Artifacts settings = properties.getArtifacts();
        boolean production = environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES));

        if ("s3".equalsIgnoreCase(settings.getStore())) {
            requireConfigured(settings.getBucket(), "platform.orchestration.artifacts.bucket",
                    "the bucket datasets are stored in");
            return new S3DatasetStore(s3Client(settings), settings.getBucket());
        }

        if (production) {
            throw new IllegalStateException(
                    "Datasets must not be stored on a pod-local filesystem in production: a file "
                            + "written by one replica is invisible to the others, and a runner "
                            + "cannot reach it at all. Set "
                            + "platform.orchestration.artifacts.store=s3.");
        }

        return new FileSystemDatasetStore(Path.of(properties.getWorkDirectory(), "datasets"));
    }

    /**
     * Uploading and reading the data a test sends.
     *
     * <p>Authorized by team like everything else: someone who can change a test's rows can change
     * what it exercises as surely as someone who can edit its journeys.
     */
    @Bean
    public ManageDatasetsUseCase manageDatasetsUseCase(DatasetStore datasetStore,
            DatasetCatalog datasetCatalog, TeamDirectory teams, Clock clock) {
        return new ManageDatasetsUseCase(datasetStore, datasetCatalog, teams, clock,
                properties.getDatasets().getMaxSize());
    }

    /**
     * The S3 client.
     *
     * <p>Credentials come from the default provider chain — environment, instance profile, or an
     * IRSA-projected service account token — never from configuration. A key in a values file is a
     * key in source control eventually (CLAUDE.md §20).
     */
    private static S3Client s3Client(OrchestrationProperties.Artifacts settings) {
        S3ClientBuilder builder = S3Client.builder();

        if (!settings.getRegion().isBlank()) {
            builder.region(Region.of(settings.getRegion()));
        }
        if (!settings.getEndpoint().isBlank()) {
            // An S3-compatible appliance or MinIO rather than AWS.
            builder.endpointOverride(URI.create(settings.getEndpoint()));
        }
        if (settings.isPathStyleAccess()) {
            builder.serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    private static void requireConfigured(String value, String property, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration '" + property + "': " + description + ".");
        }
    }

    /**
     * Where a run's traffic goes.
     *
     * <p>Resolved from the durable V2 target registry. During the migration overlap,
     * {@link TeamRegistrySynchronizer} copies non-production V1 YAML targets into that registry at
     * startup; runtime resolution itself no longer reads YAML.
     *
     * <p>{@code @Primary} because the registry's own store is also a {@code TargetResolver} — the
     * registry interface extends it — so without this the context has two candidates and refuses to
     * start at all. Naming this one as the answer keeps the resolution seam explicit: consumers ask
     * for the narrow read port and get whatever this method decides to build it from, which is what
     * lets the resolver change without touching every consumer.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public TargetResolver targetResolver(TargetRegistry targets) {
        return targets;
    }

    @Bean
    public SecretResolver secretResolver() {
        return new MountedFileSecretResolver(Path.of(properties.getSecretResolver().getMountRoot()));
    }

    @Bean
    public SessionAcquisitionProvider sessionAcquisitionProvider(SecretResolver secrets) {
        OrchestrationProperties.SessionAcquisition settings = properties.getSessionAcquisition();
        String provider = settings.getProvider() == null
                ? ""
                : settings.getProvider().trim().toLowerCase(Locale.ROOT);
        boolean production = environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES));

        if ("disabled".equals(provider)) {
            return request -> { throw new IllegalStateException("Application session acquisition is disabled on this deployment."); };
        }
        if ("runtime-credential".equals(provider)) {
            if (production) {
                throw new IllegalStateException("The runtime-credential session acquisition provider "
                        + "clones a trigger-supplied credential and must not be the production "
                        + "login/refresh strategy. Configure "
                        + "platform.orchestration.session-acquisition.provider as "
                        + "'oauth2-refresh' or 'external-session-broker' once the approved "
                        + "provider implementation is installed.");
            }
            log.warn("Using the DEVELOPMENT session acquisition provider: sessions are cloned from "
                    + "a trigger-supplied runtime credential and expire after {}.",
                    settings.getRuntimeCredentialTtl());
            return new RuntimeCredentialSessionAcquisitionProvider(
                    settings.getRuntimeCredentialTtl());
        }

        if ("oauth2-refresh".equals(provider)) {
            requireConfigured(settings.getEndpoint(),
                    "platform.orchestration.session-acquisition.endpoint",
                    "the approved session acquisition service base URL");
            requireConfigured(settings.getLoginPath(),
                    "platform.orchestration.session-acquisition.login-path",
                    "the login endpoint path");
            requireConfigured(settings.getRefreshPath(),
                    "platform.orchestration.session-acquisition.refresh-path",
                    "the refresh endpoint path");
            requireConfigured(settings.getClientCredentialSecretRef(),
                    "platform.orchestration.session-acquisition.client-credential-secret-ref",
                    "the secret reference used by the provider, never the secret value");
            throw new IllegalStateException("Session acquisition provider 'oauth2-refresh' is "
                    + "configured but no approved implementation is bundled in this increment. "
                    + "Install the provider adapter behind SessionAcquisitionProvider before "
                    + "using this mode.");
        }

        if (ExternalSessionBrokerSessionAcquisitionProvider.PROVIDER_ID.equals(provider)) {
            requireConfigured(settings.getEndpoint(),
                    "platform.orchestration.session-acquisition.endpoint",
                    "the approved session broker base URL");
            requireConfigured(settings.getClientCredentialSecretRef(),
                    "platform.orchestration.session-acquisition.client-credential-secret-ref",
                    "the secret reference used by the provider, never the secret value");
            requireMountedSecretReference(settings.getClientCredentialSecretRef(),
                    "platform.orchestration.session-acquisition.client-credential-secret-ref");
            return new ExternalSessionBrokerSessionAcquisitionProvider(
                    HttpClient.newBuilder()
                            .connectTimeout(settings.getRequestTimeout())
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build(),
                    URI.create(settings.getEndpoint()),
                    settings.getLoginPath(),
                    settings.getRefreshPath(),
                    settings.getClientCredentialSecretRef(),
                    secrets,
                    settings.getRequestTimeout());
        }

        throw new IllegalStateException("Unsupported session acquisition provider '" + provider
                + "'. Available: runtime-credential, oauth2-refresh, external-session-broker.");
    }

    private static void requireMountedSecretReference(String value, String property) {
        if (value == null || !value.trim().regionMatches(true, 0, "k8s://", 0,
                "k8s://".length())) {
            throw new IllegalStateException("Configuration '" + property
                    + "' must be a k8s:// mounted Secret reference for the bundled "
                    + "mounted-file SecretResolver.");
        }
    }

    @Bean
    public SessionPoolProvider sessionPoolProvider(SessionAcquisitionProvider acquisition) {
        return new RuntimeCredentialSessionPoolProvider(acquisition);
    }

    @Bean
    public RunWorkloadService runWorkloadService(EngineRegistry engines, ArtifactStore artifacts,
            ResultCatalog results, RunAnalyzer analyzer, TargetResolver targets,
            DatasetCatalog datasetCatalog, DatasetStore datasetStore,
            SessionPoolProvider sessionPools, ProgressCatalog progress) {
        return new RunWorkloadService(engines, artifacts, results, analyzer, targets,
                datasetCatalog, datasetStore, sessionPools, progress);
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

        if (environment.getProperty("platform.security.service-token-file", "").length() > 0) {
            return () -> {
                var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof com.perfplatform.domain.security.CallerContext caller)) {
                    throw new CallerContextProvider.UnauthenticatedException("A valid scoped service token is required.");
                }
                return caller;
            };
        }
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
            TeamProvisioning provisioning, TargetRegistry targets) {

        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException(
                    "Team registry synchronization writes business data from a config file at boot "
                            + "and must never run under a production profile. Onboard teams through "
                            + "the platform's own process (Increment 4), or set "
                            + "platform.orchestration.synchronize-teams-from-yaml=false.");
        }

        log.warn("Team registry synchronization from YAML is enabled: teams and applications "
                + "declared in the team configuration will be created if absent. Development only.");

        return new TeamRegistrySynchronizer(teamConfigs, provisioning, targets);
    }

    @Bean
    public ManageTargetsUseCase manageTargetsUseCase(TargetRegistry targets, TeamDirectory teams,
            TeamProvisioning provisioning) {
        return new ManageTargetsUseCase(targets, teams, provisioning);
    }

    /**
     * The recorder bridge this deployment runs.
     *
     * <p>A local Chrome only when it is switched on <em>and</em> the profile is not production. The
     * fallback is not null — it is {@link UnavailableRecorderBrowser}, so a session honestly reports
     * that no browser exists rather than appearing to wait for one.
     */
    @Bean
    public RecorderBrowser recorderBrowser(Clock clock) {
        OrchestrationProperties.Recorder.LocalBrowser local =
                properties.getRecorder().getLocalBrowser();

        if (!local.isEnabled()) {
            return new UnavailableRecorderBrowser(
                    "the local recorder browser is not enabled on this deployment");
        }
        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException("The local recorder browser must not be enabled under "
                    + "a production profile. It runs a user-driven Chrome inside the control-plane "
                    + "process, which CLAUDE.md §4 forbids; set "
                    + "platform.orchestration.recorder.local-browser.enabled=false and use a "
                    + "recorder cell.");
        }

        ChromeRecorderSettings settings = new ChromeRecorderSettings(
                Optional.ofNullable(local.getBinary()).filter(path -> !path.isBlank()).map(Path::of),
                local.isHeadless(),
                Duration.ofSeconds(20),
                Duration.ofSeconds(10),
                local.getFrameQuality(),
                local.getMaxCaptureBytes());

        LocalChromeRecorderBrowser browser = new LocalChromeRecorderBrowser(settings, clock);
        if (browser.isAvailable()) {
            log.warn("Local recorder browser ENABLED using {}. This is a development bridge: it "
                    + "starts Chrome inside the control-plane process.", browser.describe());
        } else {
            log.warn("Local recorder browser is enabled but no Chrome or Chromium was found. "
                    + "Recorder sessions will stay in PENDING_BROWSER_BRIDGE.");
        }
        return browser;
    }

    @Bean
    public RecorderBridgeSettings recorderBridgeSettings() {
        OrchestrationProperties.Recorder recorder = properties.getRecorder();
        boolean loopback = recorder.getLocalBrowser().isAllowLoopbackTarget();

        if (loopback && environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILES))) {
            throw new IllegalStateException("The recorder loopback-target allowance must not be "
                    + "enabled under a production profile. A recording browser that may address "
                    + "loopback can reach whatever else listens on the pod; set "
                    + "platform.orchestration.recorder.local-browser.allow-loopback-target=false.");
        }
        if (loopback) {
            log.warn("Recorder loopback targets are PERMITTED. This is local testing only: the "
                    + "address floor still denies metadata, link-local, wildcard and multicast "
                    + "addresses, but a recording browser may reach this machine's own services.");
        }

        return new RecorderBridgeSettings(
                new RecorderViewport(recorder.getViewportWidth(), recorder.getViewportHeight()),
                recorder.getIdleTimeout(),
                recorder.getDeadline(),
                recorder.getFrameBufferSize(),
                recorder.getEventBufferSize(),
                loopback ? RecorderAddressAllowances.permittingLoopback()
                        : RecorderAddressAllowances.strict());
    }

    @Bean
    public RecorderSessionRegistry recorderSessionRegistry() {
        return new RecorderSessionRegistry(properties.getRecorder().getMaxConcurrentSessions());
    }

    /**
     * Freezes a recorder target's addresses at session start.
     *
     * <p>A bean rather than a static call so a test can hand back a metadata address and prove the
     * session is refused, which is the control this resolver exists to feed.
     */
    @Bean
    public HostAddressResolver hostAddressResolver() {
        return host -> {
            try {
                return List.of(InetAddress.getAllByName(host));
            } catch (UnknownHostException e) {
                throw new HostAddressResolver.UnresolvableHostException(host, e);
            }
        };
    }

    @Bean
    public ManageRecordingsUseCase manageRecordingsUseCase(TeamDirectory teams,
            TargetRegistry targets, RecorderAuditLog audit, RecorderBrowser browser,
            HostAddressResolver hosts, RecorderSessionRegistry registry,
            RecorderBridgeSettings bridgeSettings, List<SourceImporter> importers, Clock clock) {

        // A recording is imported as a HAR, so it gets the same asset stripping, journey grouping,
        // frequency weights and correlation suggestions as a capture exported from DevTools rather
        // than a second implementation of all of it. Selected from the importers Spring already has.
        SourceImporter har = importers.stream()
                .filter(importer -> importer.sourceType() == SourceType.HAR)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The recorder needs the HAR importer to turn a recording into a draft, and "
                                + "no importer for source type HAR is registered."));

        return new ManageRecordingsUseCase(teams, targets, audit, browser, hosts, registry,
                bridgeSettings, har, clock);
    }

    @Bean
    public TriggerTestRunUseCase triggerTestRunUseCase(TestRunRepository runs,
            DefinitionVersionCatalog versions, RuntimeCredentialStore runtimeCredentials,
            com.perfplatform.domain.admission.ExecutionLimits executionLimits, Clock clock) {
        return new TriggerTestRunUseCase(runs, versions, runtimeCredentials,
                properties.getCapacity().getMaxBrowserSessionsPerRun(), executionLimits, clock);
    }

    @Bean
    public RuntimeCredentialStore runtimeCredentialStore() {
        return new InMemoryRuntimeCredentialStore();
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
     * The live view of a run in flight.
     *
     * <p>Built on the run reader for the same reason reporting is: one team check, in one place. A
     * live view is the read most likely to be polled from a dashboard nobody logged into recently,
     * so it is the last place that should have an authorization path of its own.
     */
    @Bean
    public ReadRunProgressUseCase readRunProgressUseCase(ReadTestRunUseCase runs,
            ProgressCatalog progress, DefinitionVersionCatalog versions, RunAnalyzer analyzer,
            OrchestrationSettings settings, Clock clock) {
        return new ReadRunProgressUseCase(runs, progress, versions, analyzer, settings, clock);
    }

    /**
     * The client the Validator sends its single iteration with.
     *
     * <p><b>Pinned to HTTP/1.1.</b> Java's {@code HttpClient} defaults to HTTP/2, which over
     * cleartext means attempting an {@code h2c} upgrade — and a server that answers HTTP/1.1 without
     * handling that upgrade leaves the request hanging until the timeout. Measured against a Vite
     * dev server: 10s timeout on HTTP/2, 2ms on HTTP/1.1, for the same URL that {@code curl}
     * answered instantly.
     *
     * <p>That is not a niche target. Cleartext h2c is rarely deployed and unevenly implemented, so
     * the Validator would report "the request could not reach the target" for a great many
     * applications that are up and perfectly reachable — the single worst thing a pre-flight check
     * can do, because it sends someone to debug their service instead of ours. Load engines send
     * HTTP/1.1 by default too, so this also makes the Validator resemble the run it precedes.
     */
    @Bean
    public HttpClient validatorHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getExecutionGrace())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public SpecificationDryRunService specificationDryRunService(TeamDirectory teams,
            TargetResolver targets, HttpClient validatorHttpClient,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper, Clock clock) {
        return new SpecificationDryRunService(teams, targets, validatorHttpClient, objectMapper, clock);
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
            RuntimeCredentialStore runtimeCredentials,
            RunWorkloadService workloads,
            com.perfplatform.domain.admission.ExecutionLimits executionLimits,
            Clock clock) {

        String owner = "reconciler-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Reconciler owner identity for this instance: {}", owner);

        return new RunReconciler(runs, shards, plans, versions, compiler, provider,
                admissionPolicy, limits, settings, runtimeCredentials, workloads, executionLimits, clock, owner);
    }
}
