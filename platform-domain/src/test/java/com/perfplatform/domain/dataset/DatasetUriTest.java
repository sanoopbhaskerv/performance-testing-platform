package com.perfplatform.domain.dataset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a specification names the data it sends.
 *
 * <p>The property worth protecting is that a reference is <em>pinned</em>. An unversioned reference
 * would let two runs of one immutable definition version read different rows while carrying the same
 * specification digest, which quietly breaks the promise that a run is reproducible from its stored
 * inputs.
 */
class DatasetUriTest {

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("reads a name and a version")
        void readsNameAndVersion() {
            DatasetUri uri = DatasetUri.parse("dataset://proposal-clients/7").orElseThrow();

            assertThat(uri.name()).isEqualTo("proposal-clients");
            assertThat(uri.version()).isEqualTo(7);
        }

        @Test
        @DisplayName("round-trips through its canonical text form")
        void roundTrips() {
            String text = DatasetUri.of("orders", 3).toString();

            assertThat(text).isEqualTo("dataset://orders/3");
            assertThat(DatasetUri.parse(text)).contains(DatasetUri.of("orders", 3));
        }

        @Test
        @DisplayName("tolerates surrounding whitespace, which a YAML value often carries")
        void trims() {
            assertThat(DatasetUri.parse("  dataset://orders/1  "))
                    .contains(DatasetUri.of("orders", 1));
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an unversioned reference does not parse, and is recognised as such")
        void unversioned() {
            assertThat(DatasetUri.parse("dataset://orders")).isEmpty();
            assertThat(DatasetUri.isUnversioned("dataset://orders")).isTrue();
            assertThat(DatasetUri.isUnversioned("dataset://orders/")).isTrue();
        }

        @Test
        @DisplayName("a versioned reference is not mistaken for an unversioned one")
        void versionedIsNotUnversioned() {
            assertThat(DatasetUri.isUnversioned("dataset://orders/2")).isFalse();
        }

        @Test
        @DisplayName("a bucket URL or a file path is not a dataset reference")
        void otherSchemes() {
            assertThat(DatasetUri.parse("s3://bucket/orders.csv")).isEmpty();
            assertThat(DatasetUri.parse("artifact://datasets/orders.csv")).isEmpty();
            assertThat(DatasetUri.parse("/var/data/orders.csv")).isEmpty();
            assertThat(DatasetUri.parse("orders")).isEmpty();
            assertThat(DatasetUri.parse(null)).isEmpty();
        }

        /**
         * A name reaches an object key, and a key prefix is what makes authorization structural. A
         * reference that could carry a separator or a parent segment could name another team's data.
         */
        @Test
        @DisplayName("a name cannot carry a path separator, a parent segment or uppercase")
        void nameIsRestricted() {
            assertThat(DatasetUri.parse("dataset://../other-team/1")).isEmpty();
            assertThat(DatasetUri.parse("dataset://teams/other/1")).isEmpty();
            assertThat(DatasetUri.parse("dataset://Orders/1")).isEmpty();
            assertThat(DatasetUri.parse("dataset://-orders/1")).isEmpty();
        }

        /**
         * One textual form per version. Without this, {@code /01} and {@code /1} would both parse to
         * version 1, and two specifications that differ textually would describe the same test —
         * or, worse, digest differently while resolving identically.
         */
        @Test
        @DisplayName("a version has no leading zero and starts at 1")
        void versionForm() {
            assertThat(DatasetUri.parse("dataset://orders/01")).isEmpty();
            assertThat(DatasetUri.parse("dataset://orders/0")).isEmpty();
            assertThat(DatasetUri.parse("dataset://orders/-1")).isEmpty();
        }

        @Test
        @DisplayName("constructing version 0 is refused")
        void versionZeroRefused() {
            assertThatThrownBy(() -> DatasetUri.of("orders", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
