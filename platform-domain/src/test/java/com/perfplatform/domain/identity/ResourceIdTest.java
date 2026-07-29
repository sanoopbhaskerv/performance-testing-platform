package com.perfplatform.domain.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that typed resource identifiers reject invalid input at construction and cannot be
 * substituted for one another.
 */
class ResourceIdTest {

    @Test
    @DisplayName("a generated identifier is unique and round-trips through its string form")
    void generatesUniqueRoundTrippableIds() {
        TestRunId first = TestRunId.generate();
        TestRunId second = TestRunId.generate();

        assertThat(first).isNotEqualTo(second);
        assertThat(TestRunId.of(first.toString())).isEqualTo(first);
    }

    @Test
    @DisplayName("identifiers are value objects compared by their underlying UUID")
    void comparesByValue() {
        UUID uuid = UUID.randomUUID();

        assertThat(TeamId.of(uuid)).isEqualTo(TeamId.of(uuid));
        assertThat(TeamId.of(uuid)).hasSameHashCodeAs(TeamId.of(uuid));
    }

    /**
     * The point of typed identifiers: two identifiers wrapping the same UUID are still different
     * types, so a team identifier cannot be passed where a run identifier is expected. That
     * mistake is unremarkable with bare strings and dangerous in authorization code.
     */
    @Test
    @DisplayName("different identifier types are never equal, even wrapping the same UUID")
    void distinctTypesAreNotInterchangeable() {
        UUID uuid = UUID.randomUUID();

        assertThat((Object) TeamId.of(uuid)).isNotEqualTo(TestRunId.of(uuid));
        assertThat((Object) ArtifactId.of(uuid)).isNotEqualTo(RunnerShardId.of(uuid));
    }

    @Test
    @DisplayName("a non-UUID value is rejected")
    void rejectsNonUuidValues() {
        assertThatThrownBy(() -> TestRunId.of("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TestRunId must be a valid UUID");
    }

    @Test
    @DisplayName("null and blank values are rejected with the identifier type named")
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> TeamId.of((String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TeamId must not be null or blank");

        assertThatThrownBy(() -> TeamId.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TeamId must not be null or blank");

        assertThatThrownBy(() -> TeamId.of((UUID) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("TeamId must not be null");
    }

    /**
     * Identifiers arrive from untrusted input and error messages are frequently logged, so the
     * rejected value must not be echoed back into the message.
     */
    @Test
    @DisplayName("a rejection message does not echo the invalid input")
    void doesNotEchoInvalidInput() {
        assertThatThrownBy(() -> ExecutionPlanId.of("' OR 1=1 --"))
                .hasMessageNotContaining("OR 1=1");
    }

    @Test
    @DisplayName("surrounding whitespace is tolerated when parsing")
    void trimsSurroundingWhitespace() {
        UUID uuid = UUID.randomUUID();

        assertThat(TestDefinitionId.of("  " + uuid + "  ")).isEqualTo(TestDefinitionId.of(uuid));
    }

    @Test
    @DisplayName("every identifier type required by the architecture exists and is constructible")
    void definesEveryRequiredIdentifier() {
        List<ResourceId> all = List.of(
                TeamId.generate(),
                ApplicationId.generate(),
                TestDefinitionId.generate(),
                TestDefinitionVersionId.generate(),
                ExecutionPlanId.generate(),
                TestRunId.generate(),
                RunnerShardId.generate(),
                ArtifactId.generate());

        assertThat(all)
                .hasSize(8)
                .allSatisfy(id -> assertThat(id.value()).isNotNull());
    }
}
