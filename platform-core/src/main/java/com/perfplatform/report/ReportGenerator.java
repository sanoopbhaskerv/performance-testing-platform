package com.perfplatform.report;

import com.perfplatform.engine.JmeterTestOrchestrator.JmeterRunResult;
import com.perfplatform.sla.SlaEvaluator.SlaResult;
import com.perfplatform.storage.TestRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Generates a unified HTML report combining:
 * - JMeter HTML report
 * - SLA evaluation results
 * - JVM heap/thread dump analysis (if enabled)
 */
@Service
public class ReportGenerator {

  private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);
  private static final DateTimeFormatter FMT = DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

  @Value("${platform.work-dir:/tmp/perf-platform}")
  private String workDir;

  public String generate(String runId, JmeterRunResult jmeter, SlaResult sla, TestRun run) throws IOException {
    Path runDir = Paths.get(workDir, runId);
    Path reportFile = runDir.resolve("perf-report.html");

    String slaColor = sla.passed() ? "#22c55e" : "#ef4444";
    String slaLabel = sla.passed() ? "✅ PASS" : "❌ SLA BREACH";
    String statusBadge = run.getStatus() == TestRun.Status.COMPLETED
        ? "<span class='badge pass'>COMPLETED</span>"
        : "<span class='badge fail'>FAILED</span>";

    Duration duration = (run.getStartedAt() != null && run.getCompletedAt() != null)
        ? Duration.between(run.getStartedAt(), run.getCompletedAt())
        : Duration.ZERO;

    // Link to JMeter HTML report
    String jmeterReportLink = jmeter.reportDir() != null
        ? "<a href='report/index.html' target='_blank'>Open JMeter Report →</a>"
        : "<em>Not available</em>";

    String heapSection = run.getHeapDumpReportPath() != null
        ? (run.getHeapDumpReportPath().endsWith(".hprof")
            ? "<p><a href='heap-dump/" + Paths.get(run.getHeapDumpReportPath()).getFileName()
                + "' target='_blank'>📥 Download Raw Heap Dump (.hprof) →</a></p>"
            : "<p><a href='heap-dump/mat-report/index.html' target='_blank'>📦 Open Heap Dump Analysis →</a></p>")
        : "<p class='muted'>Heap dump analysis not enabled or failed for this team config.</p>";

    String threadSection = run.getThreadDumpReportPath() != null
        ? "<p><a href='thread-dump/" + Paths.get(run.getThreadDumpReportPath()).getFileName()
            + "' target='_blank'>🧵 Open Thread Dump Report →</a></p>"
        : "<p class='muted'>Thread dump analysis not enabled for this team config.</p>";

    String html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <title>Perf Report — %s — %s</title>
          <style>
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body { font-family: 'Segoe UI', Arial, sans-serif; background: #0f172a; color: #e2e8f0; }
            header { background: linear-gradient(135deg, #1e3a5f, #0f172a); padding: 32px 48px; border-bottom: 1px solid #1e40af; }
            header h1 { font-size: 1.8rem; color: #93c5fd; letter-spacing: -0.5px; }
            header p  { color: #64748b; margin-top: 4px; font-size: 0.9rem; }
            .container { max-width: 1100px; margin: 40px auto; padding: 0 32px; }
            .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; margin-bottom: 36px; }
            .card { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 24px; }
            .card .label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 1px; color: #64748b; margin-bottom: 8px; }
            .card .value { font-size: 2rem; font-weight: 700; color: #f1f5f9; }
            .card .unit  { font-size: 0.85rem; color: #94a3b8; margin-left: 4px; }
            .sla-banner { border-radius: 12px; padding: 20px 28px; margin-bottom: 28px;
                          display: flex; align-items: center; gap: 16px;
                          background: %s22; border: 2px solid %s; }
            .sla-banner .sla-text { font-size: 1.4rem; font-weight: 700; color: %s; }
            .section { background: #1e293b; border: 1px solid #334155; border-radius: 12px; padding: 28px; margin-bottom: 24px; }
            .section h2 { font-size: 1rem; color: #93c5fd; margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid #334155; }
            table { width: 100%%; border-collapse: collapse; }
            th, td { padding: 10px 14px; text-align: left; border-bottom: 1px solid #1e293b; }
            th { color: #64748b; font-size: 0.8rem; text-transform: uppercase; }
            td { color: #e2e8f0; }
            a { color: #60a5fa; text-decoration: none; }
            a:hover { text-decoration: underline; }
            .badge { padding: 3px 10px; border-radius: 20px; font-size: 0.75rem; font-weight: 600; }
            .badge.pass { background: #14532d; color: #86efac; }
            .badge.fail { background: #7f1d1d; color: #fca5a5; }
            .muted { color: #64748b; font-size: 0.9rem; font-style: italic; }
            footer { text-align: center; padding: 32px; color: #334155; font-size: 0.8rem; }
          </style>
        </head>
        <body>
          <header>
            <h1>📊 Performance Test Report</h1>
            <p>Team: <strong>%s</strong> &nbsp;|&nbsp; Type: <strong>%s</strong> &nbsp;|&nbsp; Run ID: <code>%s</code></p>
          </header>
          <div class="container">
            <div class="sla-banner">
              <div class="sla-text">%s</div>
              <div style="color:#94a3b8; font-size:0.9rem;">%s</div>
            </div>
            <div class="grid">
              <div class="card">
                <div class="label">P95 Latency</div>
                <div class="value">%d<span class="unit">ms</span></div>
              </div>
              <div class="card">
                <div class="label">Error Rate</div>
                <div class="value">%.2f<span class="unit">%%</span></div>
              </div>
              <div class="card">
                <div class="label">Total Requests</div>
                <div class="value">%d</div>
              </div>
              <div class="card">
                <div class="label">P50 Latency</div>
                <div class="value">%d<span class="unit">ms</span></div>
              </div>
              <div class="card">
                <div class="label">P99 Latency</div>
                <div class="value">%d<span class="unit">ms</span></div>
              </div>
              <div class="card">
                <div class="label">Duration</div>
                <div class="value">%d<span class="unit">min %ds</span></div>
              </div>
            </div>
            <div class="section">
              <h2>📋 Run Details</h2>
              <table>
                <tr><th>Field</th><th>Value</th></tr>
                <tr><td>Status</td><td>%s</td></tr>
                <tr><td>Started</td><td>%s</td></tr>
                <tr><td>Completed</td><td>%s</td></tr>
                <tr><td>Failure Reason</td><td>%s</td></tr>
              </table>
            </div>
            <div class="section">
              <h2>📈 JMeter Report</h2>
              %s
            </div>
            <div class="section">
              <h2>🧠 Heap Dump Analysis</h2>
              %s
            </div>
            <div class="section">
              <h2>🧵 Thread Dump Analysis</h2>
              %s
            </div>
          </div>
          <footer>Generated by Performance Testing Platform &nbsp;·&nbsp; %s</footer>
        </body>
        </html>
        """
        .formatted(
            run.getTeam(), run.getTestType(),
            slaColor, slaColor, slaColor,
            run.getTeam(), run.getTestType(), run.getId(),
            slaLabel,
            sla.failureReason() != null ? sla.failureReason() : "All SLA thresholds met",
            sla.p95LatencyMs(), sla.errorRatePercent(),
            sla.totalRequests(),
            sla.p50LatencyMs(), sla.p99LatencyMs(),
            duration.toMinutes(), duration.toSecondsPart(),
            statusBadge,
            run.getStartedAt() != null ? FMT.format(run.getStartedAt()) : "—",
            run.getCompletedAt() != null ? FMT.format(run.getCompletedAt()) : "—",
            run.getFailureReason() != null ? run.getFailureReason() : "None",
            jmeterReportLink,
            heapSection,
            threadSection,
            FMT.format(java.time.Instant.now()));

    Files.writeString(reportFile, html);
    log.info("[{}] Unified report generated: {}", runId, reportFile);
    return reportFile.toString();
  }
}
