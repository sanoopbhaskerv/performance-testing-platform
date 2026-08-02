package com.perfplatform.api.v2;

import com.perfplatform.api.v2.dto.DatasetResponse;
import com.perfplatform.api.v2.security.CallerContextProvider;
import com.perfplatform.orchestration.dataset.ManageDatasetsUseCase;
import com.perfplatform.orchestration.dataset.ManageDatasetsUseCase.UploadResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The data a test sends.
 *
 * <p>A journey can say {@code /orders/${orderId}} and be validated against the columns its test
 * declares. This is where the rows themselves arrive.
 *
 * <h2>The upload is a CSV in the request body</h2>
 * Not multipart. There is exactly one file, its media type is known, and a raw body is what a
 * pipeline can send with {@code curl --data-binary @orders.csv} — which is how most datasets will
 * actually be uploaded, since the file usually comes out of a nightly export rather than off
 * somebody's desktop.
 *
 * <h2>Versions are allocated, never chosen</h2>
 * Uploading {@code orders} twice produces version 1 and version 2; nothing overwrites. A
 * specification pins {@code dataset://orders/2}, so pointing a test at newer data is an edit to the
 * specification — visible, reviewable, and with its own digest — rather than something that happens
 * to a test while nobody is looking.
 */
@RestController
@RequestMapping("/api/v2/datasets")
@Tag(name = "Datasets (V2)", description = "The data a test sends, stored per team and per version")
public class DatasetController {

    private final ManageDatasetsUseCase datasets;
    private final CallerContextProvider callers;

    public DatasetController(ManageDatasetsUseCase datasets, CallerContextProvider callers) {
        this.datasets = datasets;
        this.callers = callers;
    }

    @Operation(
            summary = "Upload a dataset",
            description = "Stores the CSV as the next version of the named dataset. The header row "
                    + "is required and is what a journey binds to with ${column}. Column names are "
                    + "checked: a column named as a credential or as personal data is refused and "
                    + "nothing is stored. Returns the dataset:// uri to put in a specification.")
    @PostMapping(consumes = {"text/csv", MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_OCTET_STREAM_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DatasetResponse> upload(
            @Parameter(description = "The owning team, as named in a specification's metadata.team",
                    required = true)
            @RequestParam String team,
            @Parameter(description = "Dataset name: lowercase letters, digits and hyphens",
                    required = true)
            @RequestParam String name,
            @RequestBody(required = false) String csv) {

        UploadResult result = datasets.upload(team, name,
                csv == null ? new byte[0] : csv.getBytes(StandardCharsets.UTF_8),
                callers.current());

        return ResponseEntity
                .created(URI.create("/api/v2/datasets/" + team + "/" + result.version().name()
                        + "/" + result.version().version()))
                .body(DatasetResponse.from(result.version(), result.findings()));
    }

    @Operation(
            summary = "List datasets",
            description = "Every dataset version the caller's teams hold. Column names and row "
                    + "counts only — the rows themselves are never returned.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<DatasetResponse> list() {
        return datasets.listForCaller(callers.current()).stream()
                .map(DatasetResponse::from)
                .toList();
    }

    @Operation(summary = "Get one dataset version")
    @GetMapping(value = "/{team}/{name}/{version}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DatasetResponse get(@PathVariable String team, @PathVariable String name,
            @PathVariable int version) {
        return DatasetResponse.from(datasets.get(team, name, version, callers.current()));
    }
}
