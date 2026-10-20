package com.perfplatform.domain.dataset;

import com.perfplatform.domain.dataset.DatasetPartitioner.Partition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dataset partitioning.
 *
 * <p>This is what gates raising shard count above 1, so the properties asserted here are the ones a
 * sharded run depends on: no row reaches two generators, no row is lost, every partition is a valid
 * CSV on its own, and the same input always splits the same way. Getting any of them wrong produces
 * a run that looks larger than it is — the platform's recurring failure mode.
 */
class DatasetPartitionerTest {

    private static final String ORDERS = """
            orderId,sku,quantity
            1001,ABC,1
            1002,DEF,2
            1003,GHI,3
            1004,JKL,4
            1005,MNO,5
            1006,PQR,6
            1007,STU,7
            """;

    private static List<String> allRows(List<Partition> partitions) {
        List<String> rows = new ArrayList<>();
        partitions.forEach(partition -> rows.addAll(partition.rows()));
        return rows;
    }

    @Nested
    @DisplayName("splitting")
    class Splitting {

        @Test
        @DisplayName("one shard gets the whole dataset")
        void oneShardTakesEverything() {
            List<Partition> partitions = DatasetPartitioner.partition(ORDERS, 1);

            assertThat(partitions).hasSize(1);
            assertThat(partitions.get(0).rows()).hasSize(7);
            assertThat(partitions.get(0).toCsv()).isEqualTo(ORDERS.strip());
        }

        /** The property the whole class exists for. */
        @Test
        @DisplayName("no row reaches two shards, and none is lost")
        void partitionsAreDisjointAndComplete() {
            List<Partition> partitions = DatasetPartitioner.partition(ORDERS, 3);

            List<String> rows = allRows(partitions);
            Set<String> unique = new HashSet<>(rows);

            assertThat(rows).hasSize(7);
            assertThat(unique).hasSize(7);
        }

        /**
         * Round-robin rather than contiguous blocks. Datasets arrive sorted far more often than
         * anyone intends, and contiguous slices would give each generator a systematically
         * different population to work with.
         */
        @Test
        @DisplayName("rows are dealt round-robin, not in contiguous blocks")
        void dealsRoundRobin() {
            List<Partition> partitions = DatasetPartitioner.partition(ORDERS, 3);

            assertThat(partitions.get(0).rows()).containsExactly("1001,ABC,1", "1004,JKL,4", "1007,STU,7");
            assertThat(partitions.get(1).rows()).containsExactly("1002,DEF,2", "1005,MNO,5");
            assertThat(partitions.get(2).rows()).containsExactly("1003,GHI,3", "1006,PQR,6");
        }

        @Test
        @DisplayName("every partition keeps the header, so each is a valid CSV on its own")
        void everyPartitionKeepsTheHeader() {
            List<Partition> partitions = DatasetPartitioner.partition(ORDERS, 3);

            assertThat(partitions).allSatisfy(partition -> {
                assertThat(partition.header()).isEqualTo("orderId,sku,quantity");
                assertThat(partition.toCsv()).startsWith("orderId,sku,quantity\n");
            });
        }

        @Test
        @DisplayName("the same input always splits the same way")
        void isDeterministic() {
            assertThat(DatasetPartitioner.partition(ORDERS, 4))
                    .isEqualTo(DatasetPartitioner.partition(ORDERS, 4));
        }

        /**
         * Degrades by leaving later shards empty rather than by handing everyone row 1 — a
         * duplicate would be a generator sending traffic somebody else is already sending.
         */
        @Test
        @DisplayName("fewer rows than shards leaves the later shards empty, never duplicated")
        void moreShardsThanRows() {
            List<Partition> partitions = DatasetPartitioner.partition("id\n1\n2\n", 5);

            assertThat(partitions).hasSize(5);
            assertThat(partitions.get(0).rows()).containsExactly("1");
            assertThat(partitions.get(1).rows()).containsExactly("2");
            assertThat(partitions.subList(2, 5)).allSatisfy(
                    partition -> assertThat(partition.isEmpty()).isTrue());
            assertThat(allRows(partitions)).hasSize(2);
        }

        @Test
        @DisplayName("a header with no rows produces empty partitions, not an error")
        void headerOnly() {
            List<Partition> partitions = DatasetPartitioner.partition("orderId,sku\n", 2);

            assertThat(partitions).hasSize(2);
            assertThat(allRows(partitions)).isEmpty();
            assertThat(partitions.get(0).toCsv()).isEqualTo("orderId,sku");
        }
    }

    @Nested
    @DisplayName("reading the document")
    class Reading {

        /** A dataset exported from a spreadsheet on Windows arrives with CRLF. */
        @Test
        @DisplayName("all three line endings are read, and no carriage return survives into a value")
        void handlesEveryLineEnding() {
            List<Partition> windows = DatasetPartitioner.partition("id,name\r\n1,a\r\n2,b\r\n", 1);
            List<Partition> classicMac = DatasetPartitioner.partition("id,name\r1,a\r2,b", 1);

            assertThat(windows.get(0).rows()).containsExactly("1,a", "2,b");
            assertThat(classicMac.get(0).rows()).containsExactly("1,a", "2,b");
            assertThat(windows.get(0).header()).isEqualTo("id,name");
        }

        @Test
        @DisplayName("blank lines are ignored rather than becoming empty rows")
        void ignoresBlankLines() {
            List<Partition> partitions = DatasetPartitioner.partition("id\n1\n\n\n2\n", 1);

            assertThat(partitions.get(0).rows()).containsExactly("1", "2");
        }

        @Test
        @DisplayName("column names come from the header, trimmed")
        void readsColumns() {
            assertThat(DatasetPartitioner.columnsOf("orderId, sku ,quantity\n1,a,2"))
                    .containsExactly("orderId", "sku", "quantity");
        }

        @Test
        @DisplayName("an empty document has no columns rather than one blank one")
        void emptyDocumentHasNoColumns() {
            assertThat(DatasetPartitioner.columnsOf("")).isEmpty();
            assertThat(DatasetPartitioner.columnsOf(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a shard count below one is a programming error, not user input")
        void refusesNonPositiveShardCount() {
            assertThatThrownBy(() -> DatasetPartitioner.partition(ORDERS, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("shardCount");
        }

        @Test
        @DisplayName("a document with no header cannot be partitioned")
        void refusesAHeaderlessDocument() {
            assertThatThrownBy(() -> DatasetPartitioner.partition("   ", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("header");
        }
    }
}
