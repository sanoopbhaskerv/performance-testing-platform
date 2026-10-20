package com.perfplatform.v2;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for durable run orchestration, under {@code platform.orchestration}.
 *
 * <p>Every value here is enforced server-side and none of it can be influenced by a submitted
 * specification. That separation is the point: a specification describes a test, and a test must
 * not be able to raise the ceilings that exist to stop it overwhelming a shared environment.
 */
@ConfigurationProperties(prefix = "platform.orchestration")
public class OrchestrationProperties {

    /**
     * Which execution provider runs workloads.
     *
     * <p>{@code fake} generates no load and exists to exercise the control loop. The Kubernetes
     * provider arrives in Increment 1E; until then, a production profile with {@code fake} selected
     * fails startup rather than reporting successful runs that never ran.
     */
    private String executionProvider = "fake";

    /** Whether the reconciliation loop runs. Disabled in tests that drive it explicitly. */
    private boolean reconciliationEnabled = true;

    private final LifecycleCleanup lifecycleCleanup = new LifecycleCleanup();

    /**
     * Whether V1's team YAML is mirrored into the V2 registry at startup.
     *
     * <p>A migration adapter for the V1/V2 overlap: it lets a team that can use V1 author a V2
     * definition without a separate onboarding step. Refused under a production profile — writing
     * business data from a config file at boot is acceptable only while that file is the source of
     * truth. Real onboarding arrives with identity management in Increment 4.
     */
    private boolean synchronizeTeamsFromYaml = true;

    /**
     * How often a reconciliation pass runs.
     *
     * <p>Seconds, not milliseconds. Runs last minutes to hours, so polling faster buys nothing and
     * costs a database round trip per replica per tick.
     */
    private Duration reconciliationInterval = Duration.ofSeconds(10);

    /** How long a reconciler's claim on a run holds before a peer may take it over. */
    private Duration leaseDuration = Duration.ofMinutes(2);

    /** How long a shard may go silent before it is declared lost. */
    private Duration heartbeatTolerance = Duration.ofMinutes(5);

    /** Added to a plan's duration to form the runner's hard deadline. */
    private Duration executionGrace = Duration.ofMinutes(10);

    /** How long a run may sit unvalidated before reconciliation re-decides it. */
    private Duration validationGrace = Duration.ofMinutes(1);

    /** Most runs one reconciliation pass will claim. */
    private int claimBatchSize = 20;

    /** JMeter installation, for the local execution provider. Empty means "search the usual places". */
    private String jmeterHome = "";

    /** Scratch and artifact root. Pod-local and temporary; never a durable artifact reference. */
    private String workDirectory = "/tmp/perf-platform";

    private final Capacity capacity = new Capacity();
    private final Fake fake = new Fake();
    private final RunnerCell runnerCell = new RunnerCell();
    private final Artifacts artifacts = new Artifacts();
    private final Datasets datasets = new Datasets();
    private final SecretResolverProperties secretResolver = new SecretResolverProperties();
    private final SessionAcquisition sessionAcquisition = new SessionAcquisition();
    private final Recorder recorder = new Recorder();
    private final Playwright playwright = new Playwright();

    /**
     * Cleanup of runner-owned ephemeral Kubernetes objects.
     *
     * <p>Normal reconciliation deletes runner Secrets when it observes terminal Jobs. This loop
     * exists for the less pleasant path: the control plane restarts or loses contact before that
     * observation, and the explicit delete-after timestamp on the Secret becomes the authority.
     */
    public static class LifecycleCleanup {
        private boolean enabled = true;
        private Duration interval = Duration.ofMinutes(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }

    /**
     * The data a test sends.
     *
     * <p>One setting, and it exists because of a deliberate trade. The control plane reads a whole
     * dataset in order to slice it per shard, which is what makes each shard's partition an
     * auditable stored artifact rather than something a runner decided in private. The cost is that
     * the file has to fit in memory, so the size is capped and the refusal says why.
     *
     * <p>16 MiB is a few hundred thousand rows of identifiers — comfortably more than a load test
     * consumes, and small enough that ten concurrent uploads are unremarkable.
     */
    public static class Datasets {
        private long maxSize = 16L * 1024 * 1024;

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }
    }

    /**
     * How per-run application sessions are acquired before partitioning.
     *
     * <p>{@code runtime-credential} is the foundation provider: it clones a trigger-supplied
     * credential so the delivery and partitioning contracts can be exercised locally. Production
     * must configure an approved real acquisition provider. The endpoint and secret reference fields
     * are deliberately references and coordinates, never credential values.
     */
    public static class SessionAcquisition {
        private String provider = "runtime-credential";
        private Duration runtimeCredentialTtl = Duration.ofHours(12);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private String endpoint = "";
        private String loginPath = "";
        private String refreshPath = "";
        private String clientCredentialSecretRef = "";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public Duration getRuntimeCredentialTtl() {
            return runtimeCredentialTtl;
        }

        public void setRuntimeCredentialTtl(Duration runtimeCredentialTtl) {
            this.runtimeCredentialTtl = runtimeCredentialTtl;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getLoginPath() {
            return loginPath;
        }

        public void setLoginPath(String loginPath) {
            this.loginPath = loginPath;
        }

        public String getRefreshPath() {
            return refreshPath;
        }

        public void setRefreshPath(String refreshPath) {
            this.refreshPath = refreshPath;
        }

        public String getClientCredentialSecretRef() {
            return clientCredentialSecretRef;
        }

        public void setClientCredentialSecretRef(String clientCredentialSecretRef) {
            this.clientCredentialSecretRef = clientCredentialSecretRef;
        }
    }

    /**
     * Interactive browser recording.
     *
     * <p>The recorder places a user-driven browser inside the runner network. Browser route
     * interception is useful feedback but is not enough by itself, so network enforcement defaults
     * on and production profiles refuse to disable it. Local administrators can turn it off to test
     * the rest of the recorder control flow on a development cluster that has no NetworkPolicy
     * controller.
     */
    public static class Recorder {
        private final NetworkEnforcement networkEnforcement = new NetworkEnforcement();
        private final LocalBrowser localBrowser = new LocalBrowser();

        private Duration idleTimeout = Duration.ofMinutes(5);
        private Duration deadline = Duration.ofMinutes(30);
        private Duration sweepInterval = Duration.ofSeconds(30);
        private int viewportWidth = 1_280;
        private int viewportHeight = 800;
        private int frameBufferSize = 8;
        private int eventBufferSize = 500;
        private int maxConcurrentSessions = 5;

        public NetworkEnforcement getNetworkEnforcement() {
            return networkEnforcement;
        }

        public LocalBrowser getLocalBrowser() {
            return localBrowser;
        }

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getDeadline() {
            return deadline;
        }

        public void setDeadline(Duration deadline) {
            this.deadline = deadline;
        }

        public Duration getSweepInterval() {
            return sweepInterval;
        }

        public void setSweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
        }

        public int getViewportWidth() {
            return viewportWidth;
        }

        public void setViewportWidth(int viewportWidth) {
            this.viewportWidth = viewportWidth;
        }

        public int getViewportHeight() {
            return viewportHeight;
        }

        public void setViewportHeight(int viewportHeight) {
            this.viewportHeight = viewportHeight;
        }

        public int getFrameBufferSize() {
            return frameBufferSize;
        }

        public void setFrameBufferSize(int frameBufferSize) {
            this.frameBufferSize = frameBufferSize;
        }

        public int getEventBufferSize() {
            return eventBufferSize;
        }

        public void setEventBufferSize(int eventBufferSize) {
            this.eventBufferSize = eventBufferSize;
        }

        public int getMaxConcurrentSessions() {
            return maxConcurrentSessions;
        }

        public void setMaxConcurrentSessions(int maxConcurrentSessions) {
            this.maxConcurrentSessions = maxConcurrentSessions;
        }

        public static class NetworkEnforcement {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }

        /**
         * A Chrome child process as this deployment's recorder bridge.
         *
         * <p>Off by default and refused outright under a production profile, for the same reason
         * {@code LocalJmeterExecutionProvider} is (CLAUDE.md §15): the control plane is not where
         * user-driven browsers belong. It exists so the recorder can be developed and demonstrated
         * on a laptop before a recorder cell exists.
         */
        public static class LocalBrowser {
            private boolean enabled;
            private String binary;
            private boolean headless = true;
            private boolean allowLoopbackTarget;
            private int frameQuality = 60;
            private int maxCaptureBytes = 256 * 1024;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getBinary() {
                return binary;
            }

            public void setBinary(String binary) {
                this.binary = binary;
            }

            public boolean isHeadless() {
                return headless;
            }

            public void setHeadless(boolean headless) {
                this.headless = headless;
            }

            /**
             * Permits a target that resolves to loopback.
             *
             * <p>Needed to record against a demo service on the same machine, and refused under a
             * production profile. It relaxes only the loopback rule; metadata, link-local, wildcard,
             * multicast and prohibited ranges stay denied.
             */
            public boolean isAllowLoopbackTarget() {
                return allowLoopbackTarget;
            }

            public void setAllowLoopbackTarget(boolean allowLoopbackTarget) {
                this.allowLoopbackTarget = allowLoopbackTarget;
            }

            public int getFrameQuality() {
                return frameQuality;
            }

            public void setFrameQuality(int frameQuality) {
                this.frameQuality = frameQuality;
            }

            public int getMaxCaptureBytes() {
                return maxCaptureBytes;
            }

            public void setMaxCaptureBytes(int maxCaptureBytes) {
                this.maxCaptureBytes = maxCaptureBytes;
            }
        }
    }

    /**
     * Secret resolution for infrastructure adapters.
     *
     * <p>The initial production resolver reads Kubernetes Secret volume files mounted into the
     * control plane. Configuration still names only a {@code k8s://...} reference; the value lives
     * in a Secret created and rotated outside this chart.
     */
    public static class SecretResolverProperties {
        private String mountRoot = "/var/run/perfplatform/secrets";

        public String getMountRoot() {
            return mountRoot;
        }

        public void setMountRoot(String mountRoot) {
            this.mountRoot = mountRoot;
        }
    }

    /**
     * Where and how production runner Jobs are created (CLAUDE.md §16).
     *
     * <p>Every value is an operational decision that differs per cluster, and none of it can be set
     * by a specification. A test describes the load it wants; the platform decides what a runner is
     * permitted to be. There are no defaults for namespace, service account or images: a runner cell
     * that was misconfigured but started anyway would create Jobs somewhere nobody chose.
     */
    public static class RunnerCell {
        private String namespace = "";
        private String serviceAccount = "";

        /** Engine to pinned image. An allowlist — an engine with no entry cannot be provisioned. */
        private Map<String, String> images = new LinkedHashMap<>();

        private String cpuRequest = "2";
        private String cpuLimit = "4";
        private String memoryRequest = "2Gi";
        private String memoryLimit = "4Gi";
        private String workspaceSizeLimit = "8Gi";
        private Duration finishedJobTtl = Duration.ofHours(6);
        private Duration deadlineGrace = Duration.ofMinutes(5);

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getServiceAccount() {
            return serviceAccount;
        }

        public void setServiceAccount(String serviceAccount) {
            this.serviceAccount = serviceAccount;
        }

        public Map<String, String> getImages() {
            return images;
        }

        public void setImages(Map<String, String> images) {
            this.images = images;
        }

        public String getCpuRequest() {
            return cpuRequest;
        }

        public void setCpuRequest(String cpuRequest) {
            this.cpuRequest = cpuRequest;
        }

        public String getCpuLimit() {
            return cpuLimit;
        }

        public void setCpuLimit(String cpuLimit) {
            this.cpuLimit = cpuLimit;
        }

        public String getMemoryRequest() {
            return memoryRequest;
        }

        public void setMemoryRequest(String memoryRequest) {
            this.memoryRequest = memoryRequest;
        }

        public String getMemoryLimit() {
            return memoryLimit;
        }

        public void setMemoryLimit(String memoryLimit) {
            this.memoryLimit = memoryLimit;
        }

        public String getWorkspaceSizeLimit() {
            return workspaceSizeLimit;
        }

        public void setWorkspaceSizeLimit(String workspaceSizeLimit) {
            this.workspaceSizeLimit = workspaceSizeLimit;
        }

        public Duration getFinishedJobTtl() {
            return finishedJobTtl;
        }

        public void setFinishedJobTtl(Duration finishedJobTtl) {
            this.finishedJobTtl = finishedJobTtl;
        }

        public Duration getDeadlineGrace() {
            return deadlineGrace;
        }

        public void setDeadlineGrace(Duration deadlineGrace) {
            this.deadlineGrace = deadlineGrace;
        }
    }

    /**
     * Where artifacts are stored.
     *
     * <p>{@code filesystem} for local development, {@code s3} for anything with more than one
     * replica. No bucket, region or endpoint has a default — choosing a production bucket is a
     * decision for whoever operates the platform (CLAUDE.md §31), not something this code assumes.
     */
    public static class Artifacts {
        private String store = "filesystem";
        private String bucket = "";
        private String region = "";

        /** Set only for an S3-compatible appliance or MinIO. Empty means real AWS S3. */
        private String endpoint = "";

        /**
         * The endpoint <em>runners</em> use, when it differs from the control plane's.
         *
         * <p>Empty means they are the same, which is the normal case and true for real S3. They
         * differ when object storage is reached by different routes from inside and outside the
         * cluster — in-cluster service DNS versus an external address, or a VPC endpoint versus a
         * public one. Getting this wrong is not subtle: the runner cannot fetch its plan and the run
         * fails immediately.
         */
        private String runnerEndpoint = "";

        /** Path-style addressing, which most S3-compatible servers require and AWS does not. */
        private boolean pathStyleAccess = false;

        /**
         * Name of a Kubernetes Secret in the runner namespace holding storage credentials.
         *
         * <p>Empty on EKS with real S3, where a runner's projected service-account token is enough
         * and no credential exists to leak. Needed for an S3-compatible store that only understands
         * access keys. <b>A name, never a value</b>: the platform holds a reference and kubelet
         * injects the contents, so nothing sensitive passes through the control plane or a manifest.
         */
        private String runnerCredentialsSecret = "";

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRunnerEndpoint() {
            return runnerEndpoint;
        }

        public void setRunnerEndpoint(String runnerEndpoint) {
            this.runnerEndpoint = runnerEndpoint;
        }

        /** What a runner should be told, falling back to the control plane's own endpoint. */
        public String effectiveRunnerEndpoint() {
            return runnerEndpoint == null || runnerEndpoint.isBlank() ? endpoint : runnerEndpoint;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }

        public String getRunnerCredentialsSecret() {
            return runnerCredentialsSecret;
        }

        public void setRunnerCredentialsSecret(String runnerCredentialsSecret) {
            this.runnerCredentialsSecret = runnerCredentialsSecret;
        }
    }

    public RunnerCell getRunnerCell() {
        return runnerCell;
    }

    public Artifacts getArtifacts() {
        return artifacts;
    }

    public Datasets getDatasets() {
        return datasets;
    }

    public SecretResolverProperties getSecretResolver() {
        return secretResolver;
    }

    public Recorder getRecorder() {
        return recorder;
    }

    public Playwright getPlaywright() {
        return playwright;
    }

    /**
     * Where a Playwright test may be imported from.
     *
     * <p>One setting, and it is the only thing standing between a specification and an arbitrary
     * host: a git-pinned source names something the runner will fetch and then execute. Everywhere
     * else a test states what it wants and the platform decides where the traffic goes; this keeps
     * that property rather than making the repository an exception to it.
     *
     * <p><b>Empty means nothing is permitted</b>, not "no restriction". A deployment that has not
     * declared its repositories has not made the decision, and the honest answer to a fetch request
     * under those conditions is no — with a finding that names this property, so the refusal reads
     * as a configuration gap.
     */
    public static class Playwright {
        private List<String> allowedRepositories = new ArrayList<>();

        /**
         * The checkout {@code workspace://} source paths resolve against.
         *
         * <p>Development only, and empty by default so nothing is readable until an operator names a
         * directory. It is a root rather than a list of files because it is also the boundary: the
         * runner refuses any path that resolves outside it, so setting this to a narrow directory
         * is what limits which scripts a specification can reach.
         */
        private String workspaceRoot = "";

        /** Node launcher. Empty means "search the usual places". */
        private String nodeCommand = "";

        /** npx launcher, which starts Playwright from the project's own install. */
        private String npxCommand = "";

        public List<String> getAllowedRepositories() {
            return allowedRepositories;
        }

        public void setAllowedRepositories(List<String> allowedRepositories) {
            this.allowedRepositories = allowedRepositories == null
                    ? new ArrayList<>() : allowedRepositories;
        }

        public String getWorkspaceRoot() {
            return workspaceRoot;
        }

        public void setWorkspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        public String getNodeCommand() {
            return nodeCommand;
        }

        public void setNodeCommand(String nodeCommand) {
            this.nodeCommand = nodeCommand;
        }

        public String getNpxCommand() {
            return npxCommand;
        }

        public void setNpxCommand(String npxCommand) {
            this.npxCommand = npxCommand;
        }
    }

    /**
     * Concurrency ceilings (CLAUDE.md §13).
     *
     * <p>Ten globally is the worst-case ceiling, not a target — normal operation is three or four.
     * One per team by default, raised only where policy has approved it, is what stops a single team
     * occupying every global slot and leaving nine others waiting.
     */
    public static class Capacity {
        private int globalMaxRuns = 5;
        private int defaultTeamMaxRuns = 1;
        private int maxBrowserSessionsPerRun = 20;
        private int maxProtocolUsersPerRun = 1_000;
        private Duration maxDuration = Duration.ofHours(1);

        public int getMaxProtocolUsersPerRun() { return maxProtocolUsersPerRun; }
        public void setMaxProtocolUsersPerRun(int value) { maxProtocolUsersPerRun = value; }
        public Duration getMaxDuration() { return maxDuration; }
        public void setMaxDuration(Duration value) { maxDuration = value; }

        /** Per-team ceilings by team name, for teams policy has granted more than the default. */
        private Map<String, Integer> teamOverrides = new LinkedHashMap<>();

        public int getGlobalMaxRuns() {
            return globalMaxRuns;
        }

        public void setGlobalMaxRuns(int globalMaxRuns) {
            this.globalMaxRuns = globalMaxRuns;
        }

        public int getDefaultTeamMaxRuns() {
            return defaultTeamMaxRuns;
        }

        public void setDefaultTeamMaxRuns(int defaultTeamMaxRuns) {
            this.defaultTeamMaxRuns = defaultTeamMaxRuns;
        }

        public int getMaxBrowserSessionsPerRun() {
            return maxBrowserSessionsPerRun;
        }

        public void setMaxBrowserSessionsPerRun(int maxBrowserSessionsPerRun) {
            this.maxBrowserSessionsPerRun = maxBrowserSessionsPerRun;
        }

        public Map<String, Integer> getTeamOverrides() {
            return teamOverrides;
        }

        public void setTeamOverrides(Map<String, Integer> teamOverrides) {
            this.teamOverrides = teamOverrides;
        }
    }

    /** Timings for the fake provider, so tests can model slow starts and long runs. */
    public static class Fake {
        private Duration startupDelay = Duration.ZERO;
        private Duration executionDuration = Duration.ZERO;

        public Duration getStartupDelay() {
            return startupDelay;
        }

        public void setStartupDelay(Duration startupDelay) {
            this.startupDelay = startupDelay;
        }

        public Duration getExecutionDuration() {
            return executionDuration;
        }

        public void setExecutionDuration(Duration executionDuration) {
            this.executionDuration = executionDuration;
        }
    }

    public String getExecutionProvider() {
        return executionProvider;
    }

    public void setExecutionProvider(String executionProvider) {
        this.executionProvider = executionProvider;
    }

    public boolean isReconciliationEnabled() {
        return reconciliationEnabled;
    }

    public void setReconciliationEnabled(boolean reconciliationEnabled) {
        this.reconciliationEnabled = reconciliationEnabled;
    }

    public LifecycleCleanup getLifecycleCleanup() {
        return lifecycleCleanup;
    }

    public boolean isSynchronizeTeamsFromYaml() {
        return synchronizeTeamsFromYaml;
    }

    public void setSynchronizeTeamsFromYaml(boolean synchronizeTeamsFromYaml) {
        this.synchronizeTeamsFromYaml = synchronizeTeamsFromYaml;
    }

    public Duration getReconciliationInterval() {
        return reconciliationInterval;
    }

    public void setReconciliationInterval(Duration reconciliationInterval) {
        this.reconciliationInterval = reconciliationInterval;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getHeartbeatTolerance() {
        return heartbeatTolerance;
    }

    public void setHeartbeatTolerance(Duration heartbeatTolerance) {
        this.heartbeatTolerance = heartbeatTolerance;
    }

    public Duration getExecutionGrace() {
        return executionGrace;
    }

    public void setExecutionGrace(Duration executionGrace) {
        this.executionGrace = executionGrace;
    }

    public Duration getValidationGrace() {
        return validationGrace;
    }

    public void setValidationGrace(Duration validationGrace) {
        this.validationGrace = validationGrace;
    }

    public int getClaimBatchSize() {
        return claimBatchSize;
    }

    public void setClaimBatchSize(int claimBatchSize) {
        this.claimBatchSize = claimBatchSize;
    }

    public String getJmeterHome() {
        return jmeterHome;
    }

    public void setJmeterHome(String jmeterHome) {
        this.jmeterHome = jmeterHome;
    }

    public String getWorkDirectory() {
        return workDirectory;
    }

    public void setWorkDirectory(String workDirectory) {
        this.workDirectory = workDirectory;
    }

    public Capacity getCapacity() {
        return capacity;
    }

    public Fake getFake() {
        return fake;
    }

    public SessionAcquisition getSessionAcquisition() {
        return sessionAcquisition;
    }
}
