package com.perfplatform.engine.jmeter;

import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading JMeter's results format. */
class JtlParserTest {

    private static final String HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,"
                    + "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect";

    private static String jtl(String... rows) {
        return HEADER + "\n" + String.join("\n", rows) + "\n";
    }

    @Nested
    @DisplayName("reading samples")
    class Reading {

        @Test
        @DisplayName("a well-formed row becomes a sample")
        void readsASample() {
            SampleSet parsed = JtlParser.parse(jtl(
                    "1753876800000,142,search-client,200,OK,tg1-1,text,true,,512,180,5,5,"
                            + "https://x/y,140,0,12"));

            assertThat(parsed.samples()).singleElement().satisfies(sample -> {
                assertThat(sample.operation()).isEqualTo("search-client");
                assertThat(sample.elapsedMillis()).isEqualTo(142);
                assertThat(sample.success()).isTrue();
                assertThat(sample.timestampMillis()).isEqualTo(1753876800000L);
            });
            assertThat(parsed.unparsedRows()).isZero();
        }

        @Test
        @DisplayName("a failed row is recorded as a failure, not dropped")
        void readsAFailure() {
            SampleSet parsed = JtlParser.parse(jtl(
                    "1753876800000,5000,save-proposal,500,Server Error,tg1-1,text,false,"
                            + "boom,0,180,5,5,https://x/y,4990,0,12"));

            assertThat(parsed.samples()).singleElement()
                    .extracting(Sample::success).isEqualTo(false);
        }

        /**
         * Anything other than an explicit "true" is a failure. Defaulting an unrecognized value to
         * success would report a passing test built from rows nobody could interpret.
         */
        @Test
        @DisplayName("an unrecognized success value counts as a failure")
        void unknownSuccessIsFailure() {
            SampleSet parsed = JtlParser.parse(jtl(
                    "1753876800000,10,search-client,200,OK,tg1-1,text,,,,,,,,,,"));

            assertThat(parsed.samples()).singleElement()
                    .extracting(Sample::success).isEqualTo(false);
        }

        /**
         * Column positions depend on JMeter's saveservice configuration, so a parser that indexed
         * by position would read latency out of whichever column happened to be third.
         */
        @Test
        @DisplayName("columns are located by header, not by position")
        void columnsAreLocatedByHeader() {
            String reordered = "label,success,elapsed,timeStamp\n"
                    + "search-client,true,142,1753876800000\n";

            assertThat(JtlParser.parse(reordered).samples()).singleElement()
                    .satisfies(sample -> {
                        assertThat(sample.operation()).isEqualTo("search-client");
                        assertThat(sample.elapsedMillis()).isEqualTo(142);
                    });
        }

        /**
         * A scenario name becomes the label column. One containing a comma would otherwise shift
         * every column after it, silently reading the wrong value as the latency.
         */
        @Test
        @DisplayName("a quoted label containing a comma does not shift the columns")
        void quotedLabelsAreHandled() {
            SampleSet parsed = JtlParser.parse(jtl(
                    "1753876800000,142,\"search, then open\",200,OK,tg1-1,text,true,,,,,,,,,"));

            assertThat(parsed.samples()).singleElement().satisfies(sample -> {
                assertThat(sample.operation()).isEqualTo("search, then open");
                assertThat(sample.elapsedMillis()).isEqualTo(142);
            });
        }

        @Test
        @DisplayName("an escaped quote inside a quoted field is read literally")
        void escapedQuotesAreHandled() {
            assertThat(JtlParser.splitCsv("a,\"say \"\"hi\"\"\",b"))
                    .containsExactly("a", "say \"hi\"", "b");
        }
    }

    @Nested
    @DisplayName("defect D5 — unreadable rows")
    class UnreadableRows {

        /**
         * V1 incremented its request total before parsing a row, so a row it could not read counted
         * as a request that was neither a latency sample nor an error — the error rate improved the
         * more corrupt the file was. Unreadable rows are now counted separately and stay in the
         * error-rate denominator.
         */
        @Test
        @DisplayName("a row with an unparseable elapsed time is counted, not skipped")
        void unparseableRowsAreCounted() {
            SampleSet parsed = JtlParser.parse(jtl(
                    "1753876800000,142,search-client,200,OK,tg1-1,text,true,,,,,,,,,",
                    "1753876800100,NOT_A_NUMBER,search-client,200,OK,tg1-1,text,true,,,,,,,,,",
                    "1753876800200,155,search-client,200,OK,tg1-1,text,true,,,,,,,,,"));

            assertThat(parsed.samples()).hasSize(2);
            assertThat(parsed.unparsedRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("a row shorter than the header is counted as unreadable")
        void truncatedRowsAreCounted() {
            SampleSet parsed = JtlParser.parse(jtl("1753876800000,142"));

            assertThat(parsed.samples()).isEmpty();
            assertThat(parsed.unparsedRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("a negative elapsed time is counted as unreadable rather than measured")
        void negativeElapsedIsUnreadable() {
            SampleSet parsed = JtlParser.parse(jtl(
                    "1753876800000,-5,search-client,200,OK,tg1-1,text,true,,,,,,,,,"));

            assertThat(parsed.samples()).isEmpty();
            assertThat(parsed.unparsedRows()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an empty file parses to nothing rather than failing")
        void emptyFileIsEmpty() {
            assertThat(JtlParser.parse("").samples()).isEmpty();
            assertThat(JtlParser.parse(null).samples()).isEmpty();
        }

        @Test
        @DisplayName("a file missing a required column is refused with the column named")
        void missingColumnIsRefused() {
            assertThatThrownBy(() -> JtlParser.parse("timeStamp,label,success\n1,a,true\n"))
                    .isInstanceOf(JtlParser.UnreadableResultsException.class)
                    .hasMessageContaining("elapsed");
        }

        @Test
        @DisplayName("blank lines are ignored")
        void blankLinesIgnored() {
            SampleSet parsed = JtlParser.parse(HEADER + "\n\n"
                    + "1753876800000,142,search-client,200,OK,tg1-1,text,true,,,,,,,,,\n\n");

            assertThat(parsed.samples()).hasSize(1);
            assertThat(parsed.unparsedRows()).isZero();
        }
    }

    @Test
    @DisplayName("shards are merged, keeping every sample and every unreadable row")
    void shardsMerge() {
        SampleSet first = JtlParser.parse(jtl(
                "1753876800000,142,search-client,200,OK,tg1-1,text,true,,,,,,,,,",
                "1753876800100,bad,search-client,200,OK,tg1-1,text,true,,,,,,,,,"));
        SampleSet second = JtlParser.parse(jtl(
                "1753876800200,155,search-client,200,OK,tg2-1,text,true,,,,,,,,,"));

        SampleSet merged = first.merge(second);

        assertThat(merged.samples()).hasSize(2);
        assertThat(merged.unparsedRows()).isEqualTo(1);
    }
}
