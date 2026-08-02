package com.perfplatform.domain.dataset;

import com.perfplatform.domain.validation.SensitiveFieldDetector;
import com.perfplatform.domain.validation.ValidationFinding;
import com.perfplatform.domain.validation.ValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A CSV somebody uploaded, judged before it is stored.
 *
 * <p>Every other way data enters this platform is guarded: importers refuse credential-shaped
 * values, refuse headers named as credentials, and refuse a body with a field called
 * {@code password}. A dataset upload is the one remaining door, and it is now the easiest one — a
 * spreadsheet of sign-in data is exactly what somebody reaches for after being told the importer
 * would not keep their password. So the same rule applies here, against the column names.
 *
 * <h2>What is refused outright</h2>
 * A column named as a credential or as personal data ({@code password}, {@code ssn},
 * {@code cardNumber}, …). Refused whole rather than dropped: this class understands the header, not
 * the meaning of the rows, and silently discarding a column would leave a test bound to
 * {@code ${password}} that resolves to nothing and sends the literal text. The finding names the
 * column and <b>never a value</b>, because it is returned over HTTP and written to a log.
 *
 * <p>A dataset of real customer identifiers is a real risk this cannot see — {@code customerId} is
 * an ordinary column name. That is a governance question rather than a parsing one, and the upload
 * response says so rather than implying the file was cleared.
 */
public final class DatasetDocument {

    /** How many columns a dataset may declare. Past this, something other than a dataset arrived. */
    private static final int MAX_COLUMNS = 256;

    private DatasetDocument() {
    }

    /**
     * The outcome of reading an uploaded document.
     *
     * @param columns  the header, in file order — what the specification's declared columns are
     *                 compared against
     * @param rowCount data rows, excluding the header
     * @param findings everything worth telling the uploader; blocking findings mean nothing was
     *                 stored
     */
    public record Inspection(List<String> columns, int rowCount, ValidationResult findings) {

        public Inspection {
            columns = List.copyOf(columns);
        }

        public boolean isAcceptable() {
            return findings.isValid();
        }
    }

    /**
     * Reads and judges an uploaded CSV.
     *
     * <p>Nothing here consults storage or a clock: the same bytes always produce the same verdict,
     * so an upload that was refused can be shown to be refused again without re-uploading it.
     */
    public static Inspection inspect(String csv) {
        List<ValidationFinding> findings = new ArrayList<>();

        List<String> columns = DatasetPartitioner.columnsOf(csv);
        if (columns.isEmpty()) {
            findings.add(ValidationFinding.error("dataset.header.missing", "/",
                    "The uploaded file has no header row, so its columns have no names.",
                    "The first line must name the columns, for example orderId,customerId. A "
                            + "journey binds to a column by name with ${orderId}."));
            return new Inspection(List.of(), 0, new ValidationResult(findings));
        }

        if (columns.size() > MAX_COLUMNS) {
            findings.add(ValidationFinding.error("dataset.header.tooManyColumns", "/",
                    "The header declares " + columns.size() + " columns, more than the "
                            + MAX_COLUMNS + " a dataset may have.",
                    "Check that the file is comma-separated and that its first line is a header."));
        }

        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            if (column.isBlank()) {
                findings.add(ValidationFinding.error("dataset.column.blank", "/columns/" + i,
                        "Column " + (i + 1) + " has no name, so nothing can bind to it.",
                        "Give every column a name, or remove the empty column."));
            } else if (!seen.add(column)) {
                // Two columns of one name is not a style problem: a ${name} reference would bind to
                // one of them and nothing says which, so the test would send data nobody chose.
                findings.add(ValidationFinding.error("dataset.column.duplicate", "/columns/" + i,
                        "The column '" + column + "' appears more than once. A ${" + column
                                + "} reference could not say which one it means.",
                        "Rename one of them."));
            }
        }

        SensitiveFieldDetector.Scan scan = SensitiveFieldDetector.scanNames(columns);
        if (!scan.isSafe()) {
            findings.add(ValidationFinding.error("dataset.column.sensitive", "/columns",
                    "The column '" + scan.field() + "' is named as "
                            + (scan.verdict() == SensitiveFieldDetector.Verdict.PERSONAL_DATA
                                    ? "personal data" : "a credential")
                            + ", and this platform does not store either. Nothing was stored.",
                    "Remove the column. Credentials belong in spec.authentication as a secretRef, "
                            + "resolved at execution time; a test that needs real customer data "
                            + "needs a governance decision, not a spreadsheet."));
        }

        int rowCount = dataRowCount(csv);
        if (rowCount == 0) {
            // Not an error. A dataset with a header and no rows is a valid contract that has not
            // been filled yet, and refusing it would stop somebody agreeing the columns first.
            findings.add(ValidationFinding.warning("dataset.rows.empty", "/",
                    "This dataset has a header but no rows, so a journey bound to it has nothing "
                            + "to send.",
                    "Upload a version with rows before running a test that binds to it."));
        }

        findings.add(ValidationFinding.info("dataset.contents.notInspected", "/",
                "Only the column names were checked. The platform does not know whether the rows "
                        + "hold real customer data — that remains the uploader's responsibility."));

        return new Inspection(columns, rowCount, new ValidationResult(findings));
    }

    /**
     * Data rows, counted the way {@link DatasetPartitioner} splits them.
     *
     * <p>Deliberately the same reader, so the count reported at upload is the count that gets
     * partitioned. Two implementations of "how many rows are in this file" would eventually disagree
     * about a blank final line, and the number a consumer was shown would not be the number that ran.
     */
    private static int dataRowCount(String csv) {
        return Math.max(0, DatasetPartitioner.partition(csv, 1).get(0).rows().size());
    }
}
