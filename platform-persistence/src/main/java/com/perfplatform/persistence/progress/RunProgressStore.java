package com.perfplatform.persistence.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.port.ProgressCatalog;
import com.perfplatform.domain.progress.LiveProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable storage for a run's live snapshots.
 *
 * <p>Append-only within a run, and idempotent by capture instant. Reconciliation repeats — a pass
 * may re-read a runner snapshot it has already merged — and a second identical point would draw a
 * flat stretch on a chart that reads as sustained activity rather than as nothing new.
 */
@Repository
public class RunProgressStore implements ProgressCatalog {

    private static final Logger log = LoggerFactory.getLogger(RunProgressStore.class);

    /**
     * Points returned when a caller does not cap the series.
     *
     * <p>Enough for a long run at a five-second cadence to still show its shape, and small enough
     * that reading it is never the slow part of opening a run.
     */
    public static final int DEFAULT_SERIES_LIMIT = 240;

    private final RunProgressJpaRepository progress;
    private final ObjectMapper mapper;

    public RunProgressStore(RunProgressJpaRepository progress) {
        this.progress = progress;
        // Its own mapper rather than the application's, with no modules registered. This one has
        // exactly one job — the document column's shape, which is numbers and strings — and must
        // keep reading rows written years ago. Sharing the application's mapper would let an
        // unrelated serialization setting change how a stored snapshot decodes.
        this.mapper = new ObjectMapper();
    }

    @Override
    @Transactional
    public void record(LiveProgress snapshot) {
        if (progress.existsByRunIdAndCapturedAt(snapshot.runId().value(), snapshot.capturedAt())) {
            return;
        }

        String detail = RunProgressDetail.from(snapshot).encode(mapper);

        progress.save(new RunProgressEntity(
                UUID.randomUUID(),
                snapshot.runId().value(),
                snapshot.engine(),
                snapshot.capturedAt(),
                snapshot.recordedAt(),
                snapshot.shardsExpected(),
                snapshot.shardsReporting(),
                snapshot.elapsedSeconds(),
                snapshot.activeUsers(),
                snapshot.overall().sampleCount(),
                snapshot.overall().errorCount(),
                snapshot.overall().unparsedCount(),
                snapshot.overall().throughputPerSecondMilli(),
                snapshot.overall().latency().percentileMillis(95, snapshot.overall().maxMillis()),
                detail));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LiveProgress> latest(TestRunId runId) {
        return progress.findByRunIdOrderByCapturedAtDesc(runId.value(), PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(this::toLiveProgress);
    }

    /**
     * {@inheritDoc}
     *
     * <p>When more points exist than the limit allows, the oldest is kept and the rest of the window
     * comes from the most recent. The trend rules compare now against the start of the run, and a
     * window that has forgotten the start cannot say anything has changed — a run that has been
     * degrading for twenty minutes would look, to a tail-only window, like a run that is simply slow.
     */
    @Override
    @Transactional(readOnly = true)
    public List<LiveProgress> series(TestRunId runId, int limit) {
        int capped = limit <= 0 ? DEFAULT_SERIES_LIMIT : limit;

        List<RunProgressEntity> newest = progress.findByRunIdOrderByCapturedAtDesc(
                runId.value(), PageRequest.of(0, capped));
        if (newest.isEmpty()) {
            return List.of();
        }

        List<RunProgressEntity> ordered = new ArrayList<>(newest);
        java.util.Collections.reverse(ordered);

        RunProgressEntity oldest = progress.findByRunIdOrderByCapturedAtAsc(
                        runId.value(), PageRequest.of(0, 1))
                .get(0);
        if (!oldest.getId().equals(ordered.get(0).getId())) {
            ordered.set(0, oldest);
        }

        return ordered.stream().map(this::toLiveProgress).toList();
    }

    @Override
    @Transactional
    public void clear(TestRunId runId) {
        progress.deleteByRunId(runId.value());
        log.info("Cleared live progress runId={}", runId);
    }

    private LiveProgress toLiveProgress(RunProgressEntity entity) {
        return RunProgressDetail.decode(mapper, entity.getDetailJson())
                .toLiveProgress(
                        TestRunId.of(entity.getRunId().toString()),
                        entity.getEngine(),
                        entity.getCapturedAt(),
                        entity.getRecordedAt(),
                        entity.getShardsExpected(),
                        entity.getElapsedSeconds(),
                        entity.getActiveUsers());
    }
}
