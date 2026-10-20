package com.perfplatform.v2;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.SecretResolver;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TeamDirectory;
import com.perfplatform.domain.recorder.RecorderAddressPolicy;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.RegisteredTarget;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.orchestration.run.ExternalSessionBrokerSessionAcquisitionProvider;
import com.perfplatform.orchestration.run.RuntimeCredentialSessionAcquisitionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetAddress;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards that stop development-only components reaching production.
 *
 * <p>Several components are deliberately not production-ready: an execution provider that generates
 * no load, one that generates it inside this JVM, a caller identity derived from V1 API keys, and a
 * target resolver reading V1 config. Each is useful and each is dangerous in the wrong place — a
 * platform reporting successful runs for tests that never executed is worse than one that fails to
 * start, because the reports look real and a team will act on numbers that describe nothing.
 *
 * <p>Asserted by constructing the configuration directly rather than booting a context under a
 * production profile: the expected outcome is that startup fails, and a test that asserted on a
 * context refusing to start would be slower and harder to read for the same guarantee.
 */
class ProductionSafetyGuardTest {

    @Test
    void operatorExecutionLimitsArePositiveAndConfigurable() {
        var properties = new OrchestrationProperties();
        properties.getCapacity().setMaxProtocolUsersPerRun(25);
        properties.getCapacity().setMaxDuration(java.time.Duration.ofMinutes(2));
        var limits = configurationFor(properties, "production").executionLimits();
        assertThat(limits.maxProtocolUsersPerRun()).isEqualTo(25);
        assertThat(limits.maxDuration()).isEqualTo(java.time.Duration.ofMinutes(2));
        properties.getCapacity().setMaxProtocolUsersPerRun(0);
        assertThatThrownBy(() -> configurationFor(properties).executionLimits())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatorNeverFollowsRedirectsWithoutCheckingTheirDestinations() {
        assertThat(configurationFor("production").validatorHttpClient().followRedirects())
                .isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
    }

    /** A directory in which every name resolves. */
    private static final TeamDirectory TEAMS = resolvingEverything();
    private static final TargetRegistry TARGETS = noTargets();
    private static final SecretResolver SECRETS =
            reference -> new SecretResolver.SecretMaterial(reference, "broker-client-token");

    private static TeamDirectory resolvingEverything() {
        return new TeamDirectory() {
            @Override
            public Optional<TeamId> findByName(String name) {
                return Optional.of(TeamId.generate());
            }

            @Override
            public Optional<ApplicationId> findApplication(TeamId teamId, String name) {
                return Optional.of(ApplicationId.generate());
            }
        };
    }

    /** A directory that resolves only the named teams. */
    private static TeamDirectory resolvingOnly(Map<String, TeamId> teams) {
        return new TeamDirectory() {
            @Override
            public Optional<TeamId> findByName(String name) {
                return Optional.ofNullable(teams.get(name));
            }

            @Override
            public Optional<ApplicationId> findApplication(TeamId teamId, String name) {
                return Optional.empty();
            }
        };
    }

    private static TargetRegistry noTargets() {
        return new TargetRegistry() {
            @Override
            public RegisteredTarget register(TeamId teamId, ApplicationId applicationId,
                    String applicationName, String environment, TargetClass targetClass,
                    TargetAddress address, String actor) {
                throw new UnsupportedOperationException("test target registry is read-only");
            }

            @Override
            public Optional<RegisteredTarget> find(TeamId teamId, String applicationName,
                    String environment) {
                return Optional.empty();
            }

            @Override
            public java.util.List<RegisteredTarget> listForTeam(TeamId teamId) {
                return java.util.List.of();
            }

            @Override
            public Optional<TargetAddress> resolve(TeamId teamId, String applicationName,
                    String environment) {
                return Optional.empty();
            }
        };
    }

    private static OrchestrationConfiguration configurationFor(String... activeProfiles) {
        return configurationFor(new OrchestrationProperties(), activeProfiles);
    }

    private static OrchestrationConfiguration configurationFor(OrchestrationProperties properties,
            String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return new OrchestrationConfiguration(properties, environment);
    }

    @Nested
    @DisplayName("the fake execution provider")
    class FakeProvider {

        @Test
        @DisplayName("is available for local development")
        void availableLocally() {
            // A null artifact store simply means no synthetic results are written; the provider is
            // still the one selected, which is what this asserts.
            assertThat(configurationFor("local")
                    .executionProvider(Clock.systemUTC(), null, null).providerId())
                    .isEqualTo("fake");
        }

        @Test
        @DisplayName("is refused under the production profile")
        void refusedInProduction() {
            assertThatThrownBy(() ->
                    configurationFor("production").executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must never run under a production profile")
                    // The message names the way out, so an operator is not left guessing.
                    .hasMessageContaining("execution-provider=kubernetes");
        }

        @Test
        @DisplayName("is refused under the shared-runner profile too")
        void refusedInSharedRunner() {
            assertThatThrownBy(() ->
                    configurationFor("shared-runner").executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class);
        }

        /** A provider name nobody implements must fail loudly, never fall back to the fake. */
        @Test
        @DisplayName("an unknown provider fails loudly rather than falling back")
        void unknownProviderFails() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.setExecutionProvider("k6");

            assertThatThrownBy(() -> configurationFor(properties, "local")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unsupported execution provider");
        }
    }

    /**
     * The production provider (Increment 1E).
     *
     * <p>Its configuration is validated at startup rather than at the first run. A cell missing its
     * namespace or image allowlist would otherwise fail minutes after someone triggered a test,
     * having already consumed a queue slot, and would read as a platform fault rather than the
     * deployment mistake it is.
     */
    @Nested
    @DisplayName("the Kubernetes execution provider")
    class KubernetesProvider {

        /**
         * A cell that would start. Each test below breaks exactly one thing, so a failure names the
         * guard that fired rather than whichever check happens to run first.
         */
        private OrchestrationProperties validCell() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.setExecutionProvider("kubernetes");
            properties.getRunnerCell().setNamespace("perf-runners");
            properties.getRunnerCell().setServiceAccount("perf-runner");
            properties.getRunnerCell().setImages(
                    java.util.Map.of("jmeter", "registry.internal/jmeter-runner:5.6.3-1"));
            properties.getArtifacts().setStore("s3");
            properties.getArtifacts().setBucket("perf-artifacts");
            return properties;
        }

        @Test
        @DisplayName("refuses to start without a runner namespace")
        void requiresNamespace() {
            OrchestrationProperties properties = validCell();
            properties.getRunnerCell().setNamespace("");

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("runner-cell.namespace");
        }

        @Test
        @DisplayName("refuses to start without a runner service account")
        void requiresServiceAccount() {
            OrchestrationProperties properties = validCell();
            properties.getRunnerCell().setServiceAccount("");

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("runner-cell.service-account");
        }

        /**
         * A fallback image would mean a misconfiguration produces a run against the wrong engine,
         * whose results would look perfectly valid.
         */
        @Test
        @DisplayName("refuses to start with no approved runner images")
        void requiresAnImageAllowlist() {
            OrchestrationProperties properties = validCell();
            properties.getRunnerCell().setImages(java.util.Map.of());

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No runner images are configured");
        }

        /**
         * A moving tag means two runs of the same immutable definition version can execute on
         * different runner builds: the plan digest still matches while the thing executing it has
         * changed, which quietly breaks the promise that a run is reproducible (CLAUDE.md §8).
         */
        @Test
        @DisplayName("refuses a runner image on a floating tag")
        void refusesFloatingImageTags() {
            OrchestrationProperties properties = validCell();
            properties.getRunnerCell().setImages(
                    java.util.Map.of("jmeter", "registry.internal/jmeter-runner:latest"));

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be pinned");
        }

        /**
         * A runner Pod shares no filesystem with the control plane. Without object storage it is
         * handed an artifact key and has nowhere to use it, so the Job would start and fail — after
         * consuming a queue slot, and reading like a platform fault rather than a deployment one.
         */
        @Test
        @DisplayName("refuses to start when artifacts are only on a pod-local filesystem")
        void requiresObjectStorage() {
            OrchestrationProperties properties = validCell();
            properties.getArtifacts().setStore("filesystem");

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot reach the control plane's filesystem");
        }
    }

    /**
     * Artifacts.
     *
     * <p>A pod-local file is invisible to another replica and unreachable by a runner on another
     * node. V1 persisted local paths as durable references and accumulated links nobody could open;
     * production must not be able to repeat that.
     */
    @Nested
    @DisplayName("artifact storage")
    class Artifacts {

        @Test
        @DisplayName("a pod-local filesystem is refused in production")
        void filesystemIsRefusedInProduction() {
            assertThatThrownBy(() -> configurationFor(new OrchestrationProperties(), "production")
                    .artifactStore(Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("artifacts.store=s3");
        }

        @Test
        @DisplayName("object storage refuses to start without a bucket")
        void s3RequiresABucket() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getArtifacts().setStore("s3");

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .artifactStore(Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("artifacts.bucket");
        }

        @Test
        @DisplayName("a pod-local filesystem is fine for local development")
        void filesystemIsFineLocally() {
            assertThatCode(() -> configurationFor(new OrchestrationProperties(), "local")
                    .artifactStore(Clock.systemUTC()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("recorder network enforcement")
    class RecorderNetworkEnforcement {

        @Test
        @DisplayName("is enabled by default")
        void enabledByDefault() {
            assertThat(configurationFor("local").recorderSecuritySettings()
                    .networkEnforcementEnabled()).isTrue();
        }

        @Test
        @DisplayName("can be disabled by a local administrator")
        void canBeDisabledLocally() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getRecorder().getNetworkEnforcement().setEnabled(false);

            assertThat(configurationFor(properties, "local").recorderSecuritySettings()
                    .networkEnforcementEnabled()).isFalse();
        }

        @Test
        @DisplayName("cannot be disabled under a production profile")
        void cannotBeDisabledInProduction() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getRecorder().getNetworkEnforcement().setEnabled(false);

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .recorderSecuritySettings())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Recorder network enforcement must not be disabled")
                    .hasMessageContaining("recorder.network-enforcement.enabled=true");
        }
    }

    @Nested
    @DisplayName("the Validator's HTTP client")
    class ValidatorHttpClient {

        /**
         * Java defaults to HTTP/2, which over cleartext attempts an h2c upgrade. A server that
         * answers HTTP/1.1 without handling it leaves the request hanging until the timeout —
         * measured at 10s against a Vite dev server that curl answered in milliseconds. The
         * Validator would then tell someone their healthy service was unreachable.
         */
        @Test
        @DisplayName("speaks HTTP/1.1, so an ordinary server is not reported as unreachable")
        void pinnedToHttp11() {
            assertThat(configurationFor("local").validatorHttpClient().version())
                    .isEqualTo(java.net.http.HttpClient.Version.HTTP_1_1);
        }
    }

    @Nested
    @DisplayName("the local recorder browser")
    class LocalRecorderBrowser {

        @Test
        @DisplayName("is off by default, and says so rather than appearing to be starting")
        void offByDefault() {
            var browser = configurationFor("local").recorderBrowser(Clock.systemUTC());

            assertThat(browser.isAvailable()).isFalse();
            assertThat(browser.describe()).contains("not enabled");
        }

        @Test
        @DisplayName("can be enabled for local development")
        void canBeEnabledLocally() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getRecorder().getLocalBrowser().setEnabled(true);

            assertThatCode(() -> configurationFor(properties, "local")
                    .recorderBrowser(Clock.systemUTC()))
                    .doesNotThrowAnyException();
        }

        /**
         * CLAUDE.md §4: the control plane must not be where material browser work happens. A Chrome
         * child process in the API container is precisely that.
         */
        @Test
        @DisplayName("cannot be enabled under a production profile")
        void refusedInProduction() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getRecorder().getLocalBrowser().setEnabled(true);

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .recorderBrowser(Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("local recorder browser must not be enabled")
                    .hasMessageContaining("recorder.local-browser.enabled=false");
        }

        @Test
        @DisplayName("cannot be enabled under the shared-runner profile either")
        void refusedInSharedRunner() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getRecorder().getLocalBrowser().setEnabled(true);

            assertThatThrownBy(() -> configurationFor(properties, "shared-runner")
                    .recorderBrowser(Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /**
     * Where a Playwright test may be fetched from.
     *
     * <p>The default matters more here than in most settings. A git-pinned source names a host the
     * runner will contact and then execute whatever comes back, so "not configured" must mean "no
     * repository", never "any repository" — the mistake would be invisible until the day somebody
     * pointed a definition at a repository nobody had reviewed.
     */
    @Nested
    @DisplayName("the Playwright git-source allowlist")
    class PlaywrightGitSources {

        @Test
        @DisplayName("permits nothing by default")
        void failsClosed() {
            var allowlist = configurationFor("local").gitSourceAllowlist();

            assertThat(allowlist.isConfigured()).isFalse();
            assertThat(allowlist.allows("https://github.com/anyone/anything")).isFalse();
        }

        @Test
        @DisplayName("permits exactly what an operator declared, and nothing adjacent")
        void permitsOnlyWhatIsDeclared() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getPlaywright().setAllowedRepositories(
                    List.of("https://github.com/ameriprise/perf-journeys"));

            var allowlist = configurationFor(properties, "local").gitSourceAllowlist();

            assertThat(allowlist.allows("https://github.com/ameriprise/perf-journeys")).isTrue();
            assertThat(allowlist.allows("https://github.com/ameriprise/perf-journeys-evil"))
                    .isFalse();
            assertThat(allowlist.allows("https://github.com/ameriprise")).isFalse();
        }
    }

    @Nested
    @DisplayName("the recorder loopback-target allowance")
    class RecorderLoopbackAllowance {

        @Test
        @DisplayName("is denied by default, so a recording browser cannot address this machine")
        void strictByDefault() {
            assertThat(configurationFor("local").recorderBridgeSettings().allowances().isRelaxed())
                    .isFalse();
        }

        @Test
        @DisplayName("can be granted locally, and relaxes only the loopback rule")
        void canBeGrantedLocally() throws Exception {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getRecorder().getLocalBrowser().setAllowLoopbackTarget(true);

            var allowances = configurationFor(properties, "local")
                    .recorderBridgeSettings().allowances();

            assertThat(allowances.loopbackPermitted()).isTrue();
            assertThat(RecorderAddressPolicy.check("metadata.example",
                    List.of(InetAddress.getByName("169.254.169.254")), List.of(), allowances)
                    .allowed()).isFalse();
        }

        @Test
        @DisplayName("cannot be granted under a production profile")
        void refusedInProduction() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getRecorder().getLocalBrowser().setAllowLoopbackTarget(true);

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .recorderBridgeSettings())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("loopback-target allowance must not be")
                    .hasMessageContaining("allow-loopback-target=false");
        }
    }

    @Nested
    @DisplayName("the development caller-identity provider")
    class DevelopmentIdentity {

        @Test
        @DisplayName("is available for local development")
        void availableLocally() {
            assertThatCode(() -> configurationFor("local").callerContextProvider(null, TEAMS))
                    .doesNotThrowAnyException();
        }

        /**
         * CLAUDE.md §20: production must refuse insecure development authentication. Choosing the
         * real identity provider is a pause condition, so the seam must fail closed until it is.
         */
        @Test
        @DisplayName("is refused under the production profile")
        void refusedInProduction() {
            assertThatThrownBy(() ->
                    configurationFor("production").callerContextProvider(null, TEAMS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V1 API keys")
                    .hasMessageContaining("Increment 4");
        }
    }

    @Nested
    @DisplayName("target resolution")
    class TargetResolution {

        @Test
        @DisplayName("is available for local development")
        void availableLocally() {
            assertThatCode(() -> configurationFor("local").targetResolver(TARGETS))
                    .doesNotThrowAnyException();
        }

        /**
         * Production no longer resolves targets from V1 team YAML. It uses the durable target
         * registry, whose write path enforces target policy before a run can reference an address.
         */
        @Test
        @DisplayName("uses the durable registry under the production profile")
        void durableRegistryIsUsedInProduction() {
            assertThat(configurationFor("production").targetResolver(TARGETS)).isSameAs(TARGETS);
        }
    }

    @Nested
    @DisplayName("session acquisition")
    class SessionAcquisition {

        @Test
        @DisplayName("the runtime credential provider is available for local development")
        void runtimeCredentialProviderIsAvailableLocally() {
            assertThat(configurationFor("local").sessionAcquisitionProvider(SECRETS))
                    .isInstanceOf(RuntimeCredentialSessionAcquisitionProvider.class);
        }

        @Test
        @DisplayName("the runtime credential provider is refused in production")
        void runtimeCredentialProviderIsRefusedInProduction() {
            assertThatThrownBy(() -> configurationFor("production")
                    .sessionAcquisitionProvider(SECRETS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("runtime-credential")
                    .hasMessageContaining("must not be the production");
        }

        @Test
        @DisplayName("an unknown session acquisition provider fails loudly")
        void unknownProviderFails() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getSessionAcquisition().setProvider("magic-login");

            assertThatThrownBy(() -> configurationFor(properties, "local")
                    .sessionAcquisitionProvider(SECRETS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unsupported session acquisition provider");
        }

        @Test
        @DisplayName("the oauth2 refresh seam requires endpoint and secret references")
        void oauth2RefreshRequiresCoordinates() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getSessionAcquisition().setProvider("oauth2-refresh");

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .sessionAcquisitionProvider(SECRETS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("session-acquisition.endpoint");
        }

        @Test
        @DisplayName("the approved external broker provider is wired in production")
        void externalBrokerProviderIsWired() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getSessionAcquisition().setProvider("external-session-broker");
            properties.getSessionAcquisition().setEndpoint("https://session-broker.internal");
            properties.getSessionAcquisition().setClientCredentialSecretRef(
                    "k8s://session-broker-client/token");

            assertThat(configurationFor(properties, "production")
                    .sessionAcquisitionProvider(SECRETS))
                    .isInstanceOf(ExternalSessionBrokerSessionAcquisitionProvider.class);
        }

        @Test
        @DisplayName("the bundled external broker provider requires a mounted secret reference")
        void externalBrokerRequiresMountedSecretReference() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getSessionAcquisition().setProvider("external-session-broker");
            properties.getSessionAcquisition().setEndpoint("https://session-broker.internal");
            properties.getSessionAcquisition().setClientCredentialSecretRef(
                    "vault://performance/session-broker/client");

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .sessionAcquisitionProvider(SECRETS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("k8s:// mounted Secret reference");
        }
    }

    @Nested
    @DisplayName("capacity limits")
    class Limits {

        @Test
        @DisplayName("default to the documented ceilings")
        void defaults() {
            var limits = configurationFor("local").capacityLimits(TEAMS);

            assertThat(limits.globalMaxRuns()).isEqualTo(5);
            assertThat(limits.defaultTeamMaxRuns()).isEqualTo(1);
        }

        /**
         * Dropping a stale override is the safe direction — the team falls back to the default
         * ceiling. Failing startup would take the platform down for every team because one was
         * renamed.
         */
        @Test
        @DisplayName("an override for an unknown team is dropped, not fatal")
        void unknownTeamOverrideIsDropped() {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("local");

            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getCapacity().setTeamOverrides(Map.of("no-such-team", 2));

            var limits = new OrchestrationConfiguration(properties, environment)
                    .capacityLimits(resolvingOnly(Map.of()));

            assertThat(limits.teamOverrides()).isEmpty();
            assertThat(limits.limitFor(TeamId.generate())).isEqualTo(1);
        }

        @Test
        @DisplayName("a resolved override raises only that team's ceiling")
        void resolvedOverrideApplies() {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("local");

            OrchestrationProperties properties = new OrchestrationProperties();
            properties.getCapacity().setTeamOverrides(Map.of("advice", 2));

            TeamId advice = TeamId.generate();

            var limits = new OrchestrationConfiguration(properties, environment)
                    .capacityLimits(resolvingOnly(Map.of("advice", advice)));

            assertThat(limits.limitFor(advice)).isEqualTo(2);
            assertThat(limits.limitFor(TeamId.generate())).isEqualTo(1);
        }
    }
}
