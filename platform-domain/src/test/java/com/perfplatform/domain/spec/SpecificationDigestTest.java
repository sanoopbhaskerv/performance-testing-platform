package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadModel;
import com.perfplatform.domain.spec.PerformanceTestSpecification.LoadStage;
import com.perfplatform.domain.spec.PerformanceTestSpecification.ScenarioSpec;
import com.perfplatform.domain.spec.PerformanceTestSpecification.SpecMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The digest is what makes a run reproducible and what runner results are checked against, so
 * these tests pin both halves of the contract: equivalent specifications must agree, and
 * materially different ones must not.
 */
class SpecificationDigestTest {

    @Test
    @DisplayName("identical specifications produce identical digests")
    void identicalSpecificationsAgree() {
        assertThat(SpecificationDigest.of(SpecificationFixtures.apiOnly()))
                .isEqualTo(SpecificationDigest.of(SpecificationFixtures.apiOnly()));
    }

    @Test
    @DisplayName("the digest is stable across repeated computation")
    void digestIsStable() {
        PerformanceTestSpecification spec = SpecificationFixtures.apiOnly();
        String first = SpecificationDigest.of(spec).value();

        for (int i = 0; i < 25; i++) {
            assertThat(SpecificationDigest.of(spec).value()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("the digest is rendered as sha256:<lowercase hex>")
    void digestIsAlgorithmQualified() {
        SpecificationDigest digest = SpecificationDigest.of(SpecificationFixtures.apiOnly());

        assertThat(digest.algorithm()).isEqualTo("sha256");
        assertThat(digest.hex()).matches("^[0-9a-f]{64}$");
        assertThat(digest.value()).startsWith("sha256:");
    }

    /**
     * Label order is an artifact of how the YAML was written, not of what the test does. If it
     * changed the digest, reformatting a file would look like a new test and force a new version.
     */
    @Test
    @DisplayName("label declaration order does not change the digest")
    void labelOrderIsIrrelevant() {
        Map<String, String> oneOrder = new LinkedHashMap<>();
        oneOrder.put("tier", "tier-0");
        oneOrder.put("portfolio", "awm");

        Map<String, String> otherOrder = new LinkedHashMap<>();
        otherOrder.put("portfolio", "awm");
        otherOrder.put("tier", "tier-0");

        assertThat(digestWithLabels(oneOrder)).isEqualTo(digestWithLabels(otherOrder));
    }

    @Test
    @DisplayName("equivalent duration spellings produce the same digest")
    void equivalentDurationsAgree() {
        PerformanceTestSpecification inMinutes = withStageDuration(Duration.ofMinutes(60));
        PerformanceTestSpecification inHours = withStageDuration(Duration.ofHours(1));

        assertThat(SpecificationDigest.of(inMinutes)).isEqualTo(SpecificationDigest.of(inHours));
    }

    @Test
    @DisplayName("equivalent threshold spellings produce the same digest")
    void equivalentThresholdsAgree() {
        assertThat(Threshold.parse("1s")).isEqualTo(Threshold.parse("1000ms"));
        assertThat(Threshold.parse("8s").canonicalForm()).isEqualTo("8000ms");
    }

    @Test
    @DisplayName("changing a load stage's target users changes the digest")
    void materialChangeChangesDigest() {
        PerformanceTestSpecification original = SpecificationFixtures.apiOnly();
        PerformanceTestSpecification changed = withStages(List.of(
                new LoadStage(Duration.ofMinutes(5), 200),
                new LoadStage(Duration.ofMinutes(20), 601),
                new LoadStage(Duration.ofMinutes(5), 0)));

        assertThat(SpecificationDigest.of(changed)).isNotEqualTo(SpecificationDigest.of(original));
    }

    /**
     * Stages run in sequence, so a ramp-up-then-down test and a ramp-down-then-up test are
     * different tests even though they contain the same stages.
     */
    @Test
    @DisplayName("reordering load stages changes the digest")
    void stageOrderIsSignificant() {
        PerformanceTestSpecification ascending = withStages(List.of(
                new LoadStage(Duration.ofMinutes(5), 100),
                new LoadStage(Duration.ofMinutes(5), 500)));
        PerformanceTestSpecification descending = withStages(List.of(
                new LoadStage(Duration.ofMinutes(5), 500),
                new LoadStage(Duration.ofMinutes(5), 100)));

        assertThat(SpecificationDigest.of(ascending))
                .isNotEqualTo(SpecificationDigest.of(descending));
    }

    @Test
    @DisplayName("reordering scenario weights changes the digest")
    void scenarioOrderIsSignificant() {
        PerformanceTestSpecification original = SpecificationFixtures.apiOnly();
        PerformanceTestSpecification reweighted = withScenarios(List.of(
                new ScenarioSpec("search-client", 30, "searchClient"),
                new ScenarioSpec("open-proposal", 60, "getProposal"),
                new ScenarioSpec("save-proposal", 10, "saveProposal")));

        assertThat(SpecificationDigest.of(reweighted))
                .isNotEqualTo(SpecificationDigest.of(original));
    }

    @Test
    @DisplayName("the three example shapes produce three different digests")
    void differentShapesDiffer() {
        SpecificationDigest api = SpecificationDigest.of(SpecificationFixtures.apiOnly());
        SpecificationDigest browser = SpecificationDigest.of(SpecificationFixtures.browserOnly());
        SpecificationDigest hybrid = SpecificationDigest.of(SpecificationFixtures.hybrid());

        assertThat(List.of(api, browser, hybrid)).doesNotHaveDuplicates();
    }

    /**
     * Without escaping, a value containing the field delimiter could make two different
     * specifications canonicalize to the same text and therefore share a digest.
     */
    @Test
    @DisplayName("a value containing a delimiter cannot forge canonical structure")
    void delimitersInValuesCannotForgeStructure() {
        SpecificationDigest forged = digestWithLabels(
                Map.of("a", "x\nmetadata.team=attacker"));
        SpecificationDigest plain = digestWithLabels(Map.of("a", "x"));

        assertThat(forged).isNotEqualTo(plain);
        assertThat(SpecificationCanonicalizer.canonicalize(specWithLabels(
                Map.of("a", "x\nmetadata.team=attacker"))))
                .doesNotContain("\nmetadata.team=attacker\n");
    }

    @Test
    @DisplayName("a null specification digests to a stable marker rather than throwing")
    void nullSpecificationIsStable() {
        assertThat(SpecificationDigest.of(null)).isEqualTo(SpecificationDigest.of(null));
    }

    @Test
    @DisplayName("the canonical form is version-tagged so a future encoding change is detectable")
    void canonicalFormIsVersioned() {
        assertThat(SpecificationCanonicalizer.canonicalize(SpecificationFixtures.apiOnly()))
                .startsWith("canonicalForm=" + SpecificationCanonicalizer.CANONICAL_FORM_VERSION);
    }

    @Test
    @DisplayName("a stored digest round-trips through parse")
    void parsesStoredForm() {
        SpecificationDigest digest = SpecificationDigest.of(SpecificationFixtures.apiOnly());

        assertThat(SpecificationDigest.parse(digest.value())).isEqualTo(digest);
    }

    @Test
    @DisplayName("a malformed digest is rejected")
    void rejectsMalformedDigest() {
        assertThatThrownBy(() -> SpecificationDigest.parse("deadbeef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<algorithm>:<hex>");
        assertThatThrownBy(() -> SpecificationDigest.parse("sha256:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("digest comparison is exact")
    void comparesDigestsExactly() {
        SpecificationDigest api = SpecificationDigest.of(SpecificationFixtures.apiOnly());
        SpecificationDigest browser = SpecificationDigest.of(SpecificationFixtures.browserOnly());

        assertThat(api.matches(api)).isTrue();
        assertThat(api.matches(browser)).isFalse();
        assertThat(api.matches(null)).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static PerformanceTestSpecification specWithLabels(Map<String, String> labels) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(),
                new SpecMetadata(base.metadata().name(), base.metadata().team(),
                        base.metadata().application(), labels),
                base.spec());
    }

    private static SpecificationDigest digestWithLabels(Map<String, String> labels) {
        return SpecificationDigest.of(specWithLabels(labels));
    }

    private static PerformanceTestSpecification withStageDuration(Duration duration) {
        return withStages(List.of(new LoadStage(duration, 200)));
    }

    private static PerformanceTestSpecification withStages(List<LoadStage> stages) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var workload = base.spec().workloads().get(0);
        var replaced = new PerformanceTestSpecification.WorkloadSpec(
                workload.name(), workload.type(), workload.engine(), workload.sourceRef(),
                new LoadModel(LoadModelType.STAGES, stages), workload.scenarios(),
                workload.startWhen(), workload.browserUsers(), workload.iterationsPerUser());
        return replaceWorkload(base, replaced);
    }

    private static PerformanceTestSpecification withScenarios(List<ScenarioSpec> scenarios) {
        PerformanceTestSpecification base = SpecificationFixtures.apiOnly();
        var workload = base.spec().workloads().get(0);
        var replaced = new PerformanceTestSpecification.WorkloadSpec(
                workload.name(), workload.type(), workload.engine(), workload.sourceRef(),
                workload.model(), scenarios, workload.startWhen(),
                workload.browserUsers(), workload.iterationsPerUser());
        return replaceWorkload(base, replaced);
    }

    private static PerformanceTestSpecification replaceWorkload(
            PerformanceTestSpecification base, PerformanceTestSpecification.WorkloadSpec workload) {
        var body = base.spec();
        return new PerformanceTestSpecification(base.apiVersion(), base.kind(), base.metadata(),
                new PerformanceTestSpecification.TestSpec(body.environment(), body.sources(),
                        body.authentication(), body.datasets(), List.of(workload),
                        body.objectives(), body.observability(), body.governance()));
    }
}
