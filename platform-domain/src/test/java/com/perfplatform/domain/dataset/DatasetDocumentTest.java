package com.perfplatform.domain.dataset;

import com.perfplatform.domain.validation.ValidationFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is checked before a dataset is stored.
 *
 * <p>Every other way data enters this platform is guarded: importers refuse credential-shaped
 * values, headers named as credentials and bodies with a {@code password} field. A CSV upload is the
 * remaining door, and it is the easiest one — a spreadsheet of sign-in data is precisely what
 * somebody reaches for after being told the importer would not keep their password.
 */
class DatasetDocumentTest {

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("takes the columns from the header and counts the rest as rows")
        void columnsAndRows() {
            var inspection = DatasetDocument.inspect("""
                    orderId,customerId
                    O-1,C-1
                    O-2,C-2
                    """);

            assertThat(inspection.isAcceptable()).isTrue();
            assertThat(inspection.columns()).containsExactly("orderId", "customerId");
            assertThat(inspection.rowCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("trims column names, which a spreadsheet export pads")
        void trimsColumns() {
            assertThat(DatasetDocument.inspect("orderId , customerId\nO-1,C-1").columns())
                    .containsExactly("orderId", "customerId");
        }

        /**
         * The count reported at upload has to be the count that gets partitioned, or a consumer is
         * shown a number that is not the number that ran.
         */
        @Test
        @DisplayName("counts rows the way the partitioner splits them")
        void countsAsThePartitionerDoes() {
            String csv = "orderId\r\nO-1\r\nO-2\r\n\r\n";

            assertThat(DatasetDocument.inspect(csv).rowCount())
                    .isEqualTo(DatasetPartitioner.partition(csv, 1).get(0).rows().size())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("says plainly that only the column names were looked at")
        void saysWhatWasNotChecked() {
            assertThat(codesOf(DatasetDocument.inspect("orderId\nO-1")))
                    .contains("dataset.contents.notInspected");
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a file with no header is refused: nothing could bind to it")
        void headerRequired() {
            var inspection = DatasetDocument.inspect("");

            assertThat(inspection.isAcceptable()).isFalse();
            assertThat(codesOf(inspection)).contains("dataset.header.missing");
        }

        /**
         * The case a value check cannot reach. Nothing about {@code Sunflower2019!} is token-shaped
         * — it is simply a real person's real password — so the column <em>name</em> is what has to
         * be judged.
         */
        @Test
        @DisplayName("a column named as a credential is refused, and nothing is stored")
        void credentialColumn() {
            var inspection = DatasetDocument.inspect("""
                    username,password
                    a.smith,Sunflower2019!
                    """);

            assertThat(inspection.isAcceptable()).isFalse();
            assertThat(codesOf(inspection)).contains("dataset.column.sensitive");
        }

        @Test
        @DisplayName("a column named as personal data is refused too")
        void personalDataColumn() {
            var inspection = DatasetDocument.inspect("customerId,ssn\nC-1,123-45-6789");

            assertThat(inspection.isAcceptable()).isFalse();
            assertThat(codesOf(inspection)).contains("dataset.column.sensitive");
        }

        /** A finding travels over HTTP and into a log. It may name the column; never the value. */
        @Test
        @DisplayName("no finding ever echoes a value from the file")
        void neverEchoesAValue() {
            var inspection = DatasetDocument.inspect("""
                    username,password
                    a.smith,Sunflower2019!
                    """);

            assertThat(inspection.findings().findings())
                    .allSatisfy(finding -> {
                        assertThat(finding.message()).doesNotContain("Sunflower2019!");
                        assertThat(finding.message()).doesNotContain("a.smith");
                        assertThat(String.valueOf(finding.remediation()))
                                .doesNotContain("Sunflower2019!");
                    });
            assertThat(messagesOf(inspection)).anyMatch(message -> message.contains("password"));
        }

        @Test
        @DisplayName("two columns of one name are refused: a reference could not say which")
        void duplicateColumn() {
            var inspection = DatasetDocument.inspect("orderId,orderId\nO-1,O-2");

            assertThat(inspection.isAcceptable()).isFalse();
            assertThat(codesOf(inspection)).contains("dataset.column.duplicate");
        }

        @Test
        @DisplayName("a nameless column is refused")
        void blankColumn() {
            var inspection = DatasetDocument.inspect("orderId,,customerId\nO-1,,C-1");

            assertThat(inspection.isAcceptable()).isFalse();
            assertThat(codesOf(inspection)).contains("dataset.column.blank");
        }

        /**
         * A header and no rows is a contract somebody is agreeing before filling it. Warned about,
         * not refused — refusing it would stop the columns being settled first.
         */
        @Test
        @DisplayName("a header with no rows is a warning, not a refusal")
        void emptyIsAWarning() {
            var inspection = DatasetDocument.inspect("orderId,customerId");

            assertThat(inspection.isAcceptable()).isTrue();
            assertThat(codesOf(inspection)).contains("dataset.rows.empty");
        }
    }

    private static java.util.List<String> codesOf(DatasetDocument.Inspection inspection) {
        return inspection.findings().findings().stream().map(ValidationFinding::code).toList();
    }

    private static java.util.List<String> messagesOf(DatasetDocument.Inspection inspection) {
        return inspection.findings().findings().stream().map(ValidationFinding::message).toList();
    }
}
