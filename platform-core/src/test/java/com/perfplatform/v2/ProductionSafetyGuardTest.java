package com.perfplatform.v2;

import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TeamDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
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

    /** A directory in which every name resolves. */
    private static final TeamDirectory TEAMS = resolvingEverything();

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

        private OrchestrationProperties kubernetesProperties() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.setExecutionProvider("kubernetes");
            return properties;
        }

        @Test
        @DisplayName("refuses to start without a runner namespace")
        void requiresNamespace() {
            assertThatThrownBy(() -> configurationFor(kubernetesProperties(), "production")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("runner-cell.namespace");
        }

        @Test
        @DisplayName("refuses to start without a runner service account")
        void requiresServiceAccount() {
            OrchestrationProperties properties = kubernetesProperties();
            properties.getRunnerCell().setNamespace("perf-runners");

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
            OrchestrationProperties properties = kubernetesProperties();
            properties.getRunnerCell().setNamespace("perf-runners");
            properties.getRunnerCell().setServiceAccount("perf-runner");

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
            OrchestrationProperties properties = kubernetesProperties();
            properties.getRunnerCell().setNamespace("perf-runners");
            properties.getRunnerCell().setServiceAccount("perf-runner");
            properties.getRunnerCell().setImages(
                    java.util.Map.of("jmeter", "registry.internal/jmeter-runner:latest"));

            assertThatThrownBy(() -> configurationFor(properties, "production")
                    .executionProvider(Clock.systemUTC(), null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must be pinned");
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
    @DisplayName("the V1 team-YAML target resolver")
    class TargetResolution {

        @Test
        @DisplayName("is available for local development")
        void availableLocally() {
            assertThatCode(() -> configurationFor("local").targetResolver(null, null))
                    .doesNotThrowAnyException();
        }

        /**
         * A production deployment needs environment classification and a hostname allowlist
         * (CLAUDE.md §20), neither of which V1's team YAML can express. Enabling production or
         * unrestricted targets is a pause condition (§31), so this fails closed.
         */
        @Test
        @DisplayName("is refused under the production profile")
        void refusedInProduction() {
            assertThatThrownBy(() -> configurationFor("production").targetResolver(null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("hostname allowlist");
        }
    }

    @Nested
    @DisplayName("capacity limits")
    class Limits {

        @Test
        @DisplayName("default to the documented ceilings")
        void defaults() {
            var limits = configurationFor("local").capacityLimits(TEAMS);

            assertThat(limits.globalMaxRuns()).isEqualTo(10);
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
