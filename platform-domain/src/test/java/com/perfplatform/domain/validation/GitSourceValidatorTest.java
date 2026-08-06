package com.perfplatform.domain.validation;

import com.perfplatform.domain.spec.GitSourceAllowlist;
import com.perfplatform.domain.spec.LoadModelType;
import com.perfplatform.domain.spec.PerformanceTestSpecification;
import com.perfplatform.domain.spec.PerformanceTestSpecification.EnvironmentSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SourceSpec.GitSource;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import com.perfplatform.domain.spec.PerformanceTestSpecification.TestSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.WorkloadSpec;
import com.perfplatform.domain.spec.SourceType;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.spec.WorkloadType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a Playwright test may be fetched from.
 *
 * <p>A git-pinned source is the only field in a specification that names a host the platform will
 * contact and then execute what comes back. Every other "where" is resolved server-side, so this is
 * the check that keeps that property intact rather than making the repository an exception to it.
 */
class GitSourceValidatorTest {

    private static final GitSourceAllowlist ALLOWED =
            GitSourceAllowlist.of(List.of("https://github.com/ameriprise/perf-journeys"));

    @Nested
    @DisplayName("the allowlist")
    class Allowlist {

        @Test
        @DisplayName("an allowlisted repository passes")
        void allowlistedRepositoryPasses() {
            assertThat(validate("https://github.com/ameriprise/perf-journeys", ALLOWED).isValid())
                    .isTrue();
        }

        @Test
        @DisplayName("a repository nobody declared is refused, and the message says why")
        void unknownRepositoryIsRefused() {
            ValidationResult result = validate("https://github.com/someone/else", ALLOWED);

            assertThat(result.isValid()).isFalse();
            assertThat(result.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("spec.source.git.repository.notAllowed");
                assertThat(finding.remediation()).contains("cannot choose where its code is fetched");
            });
        }

        /**
         * The important default. An operator who has configured nothing has not decided, and the
         * safe reading of that is "no", not "any".
         */
        @Test
        @DisplayName("a deployment that declared nothing permits nothing")
        void unconfiguredDeploymentRefusesEverything() {
            ValidationResult result = validate("https://github.com/ameriprise/perf-journeys",
                    GitSourceAllowlist.none());

            assertThat(result.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("spec.source.git.repository.noneAllowed");
                // Names the property, so this reads as configuration rather than as a bad test.
                assertThat(finding.remediation()).contains("allowed-repositories");
            });
        }

        @Test
        @DisplayName("the same repository written three ways is one repository")
        void normalizesSpelling() {
            assertThat(ALLOWED.allows("https://github.com/Ameriprise/Perf-Journeys.git")).isTrue();
            assertThat(ALLOWED.allows("https://github.com/ameriprise/perf-journeys/")).isTrue();
            assertThat(ALLOWED.allows("https://github.com/ameriprise/perf-journeys")).isTrue();
        }
    }

    @Nested
    @DisplayName("the URL itself")
    class Url {

        /**
         * `https://user:token@host/org/repo` is a working clone URL and a credential written into a
         * versioned, digested, findings-echoing specification. Refused before the allowlist is even
         * consulted, so an otherwise-permitted repository cannot carry one in.
         */
        @Test
        @DisplayName("credentials in the URL are refused before anything else is checked")
        void refusesCredentialsInTheUrl() {
            ValidationResult result = validate(
                    "https://x-access-token:ghp_secret@github.com/ameriprise/perf-journeys", ALLOWED);

            assertThat(result.findings()).singleElement().satisfies(finding ->
                    assertThat(finding.code())
                            .isEqualTo("spec.source.git.repository.credential"));
        }

        @Test
        @DisplayName("a local or plaintext clone URL is refused")
        void refusesUnverifiableSchemes() {
            for (String repository : List.of("file:///etc", "http://internal/repo", "/srv/repo")) {
                assertThat(validate(repository, ALLOWED).findings())
                        .as(repository)
                        .anySatisfy(finding -> assertThat(finding.code())
                                .isEqualTo("spec.source.git.repository.scheme"));
            }
        }

        /**
         * `git@host` is how everybody clones over ssh. Treating a bare ssh username as a credential
         * would refuse the ordinary case and teach people that the check is wrong.
         */
        @Test
        @DisplayName("a bare ssh username is not a credential")
        void acceptsSsh() {
            GitSourceAllowlist ssh =
                    GitSourceAllowlist.of(List.of("ssh://git@git.internal/perf/journeys"));
            assertThat(validate("ssh://git@git.internal/perf/journeys", ssh).isValid()).isTrue();
        }

        @Test
        @DisplayName("an ssh URL carrying a password is still a credential")
        void refusesSshPassword() {
            GitSourceAllowlist ssh =
                    GitSourceAllowlist.of(List.of("ssh://git@git.internal/perf/journeys"));

            assertThat(validate("ssh://git:hunter2@git.internal/perf/journeys", ssh).findings())
                    .singleElement()
                    .satisfies(finding -> assertThat(finding.code())
                            .isEqualTo("spec.source.git.repository.credential"));
        }

        /** A lone token as the https username is the exact shape a leaked PAT takes. */
        @Test
        @DisplayName("a bare token as an https username is refused")
        void refusesHttpsTokenAsUsername() {
            assertThat(validate("https://ghp_livetoken@github.com/ameriprise/perf-journeys", ALLOWED)
                    .findings())
                    .singleElement()
                    .satisfies(finding -> assertThat(finding.code())
                            .isEqualTo("spec.source.git.repository.credential"));
        }
    }

    @Test
    @DisplayName("a source with no git block is not this validator's business")
    void ignoresNonGitSources() {
        PerformanceTestSpecification specification = specification(new SourceSpec(
                "journey", SourceType.PLAYWRIGHT,
                "artifact://playwright-sources/journey.spec.ts",
                "sha256:" + "a".repeat(64), null));

        assertThat(GitSourceValidator.validate(specification, GitSourceAllowlist.none()).isValid())
                .isTrue();
    }

    private static ValidationResult validate(String repository, GitSourceAllowlist allowlist) {
        return GitSourceValidator.validate(
                specification(new SourceSpec("journey", SourceType.PLAYWRIGHT, null,
                        new GitSource(repository, "a".repeat(40), "tests/checkout.spec.ts", null))),
                allowlist);
    }

    private static PerformanceTestSpecification specification(SourceSpec source) {
        WorkloadSpec workload = new WorkloadSpec("browser", WorkloadType.BROWSER, "playwright",
                "journey", new LoadModel(LoadModelType.STAGES,
                List.of(new LoadStage(Duration.ofSeconds(1), 1))), List.of(),
                null, null, null);
        return new PerformanceTestSpecification(
                PerformanceTestSpecification.SUPPORTED_API_VERSION,
                PerformanceTestSpecification.SUPPORTED_KIND,
                new SpecMetadata("checkout", "team-a", "demo-service", Map.of()),
                new TestSpec(new EnvironmentSpec("performance", "local", TargetClass.NON_PRODUCTION),
                        List.of(source), List.of(), List.of(), List.of(workload), List.of(),
                        null, null));
    }
}
