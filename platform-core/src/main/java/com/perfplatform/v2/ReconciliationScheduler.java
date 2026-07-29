package com.perfplatform.v2;

import com.perfplatform.orchestration.run.ReconciliationReport;
import com.perfplatform.orchestration.run.RunReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Runs a reconciliation pass on a timer.
 *
 * <p>The only thing in the platform that starts work on its own, and deliberately the thinnest
 * possible wrapper: it decides <em>when</em> to reconcile and nothing about <em>how</em>. Every
 * decision lives in {@link RunReconciler}, which is a plain class and testable without a scheduler.
 *
 * <p>Fixed <em>delay</em>, not fixed rate: passes must not overlap. A pass that takes longer than
 * the interval — a slow provider, a large batch — would otherwise start again while the previous one
 * was still working, and two passes on one replica would contend for the same claims.
 *
 * <p>Every replica runs this. That is intended, and safe: claiming is what arbitrates between them,
 * and a single scheduler would be a single point of failure with no recovery.
 *
 * <h2>Why the task is registered in code</h2>
 * {@code @Scheduled(fixedDelayString = ...)} would re-read the interval as a raw string and accept
 * only milliseconds or ISO-8601, so the configuration would have to read {@code PT10S} while every
 * other duration in the file reads {@code 10s}. Registering the task here uses the already-parsed
 * {@link Duration} from {@link OrchestrationProperties}, so there is one value, in one format,
 * parsed once.
 */
@Component
@ConditionalOnProperty(prefix = "platform.orchestration", name = "reconciliation-enabled",
        havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final RunReconciler reconciler;
    private final Duration interval;

    public ReconciliationScheduler(RunReconciler reconciler, OrchestrationProperties properties) {
        this.reconciler = reconciler;
        this.interval = properties.getReconciliationInterval();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        log.info("Reconciliation loop enabled: every {} owner={}", interval, reconciler.owner());
        registrar.addFixedDelayTask(this::reconcile, interval);
    }

    /**
     * One pass.
     *
     * <p>No initial delay. A control plane that has just restarted is exactly the one holding runs
     * whose leases lapsed while it was down, and making them wait an extra interval delays recovery
     * for no benefit.
     */
    public void reconcile() {
        try {
            ReconciliationReport report = reconciler.reconcile();
            if (!report.isIdle()) {
                log.info("Reconciliation pass owner={} validated={} admitted={} advanced={} "
                                + "completed={} failed={} cancelled={} errors={}",
                        reconciler.owner(), report.validated(), report.admitted(), report.advanced(),
                        report.completed(), report.failed(), report.cancelled(), report.errors());
            }
        } catch (RuntimeException e) {
            // Never propagate: an exception out of a scheduled task cancels the schedule in Spring,
            // and a control loop that stops running is how a run is silently abandoned. Individual
            // run failures are already contained inside the reconciler; this catches whatever it
            // could not, such as the database being unreachable.
            log.error("Reconciliation pass failed owner={}", reconciler.owner(), e);
        }
    }
}
