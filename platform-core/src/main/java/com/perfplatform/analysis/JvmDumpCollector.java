package com.perfplatform.analysis;

import com.perfplatform.config.TeamConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects JVM heap dumps and thread dumps from target services via JMX.
 *
 * IMPORTANT: This service is only invoked when the team config has
 * analysis.heapDump.enabled = true OR analysis.threadDump.enabled = true
 *
 * For non-JVM services, these flags default to false and this code is never
 * called.
 */
@Service
public class JvmDumpCollector {

    private static final Logger log = LoggerFactory.getLogger(JvmDumpCollector.class);
    private static final String HOTSPOT_BEAN = "com.sun.management:type=HotSpotDiagnostic";

    @Value("${platform.work-dir:/tmp/perf-platform}")
    private String workDir;

    @Value("${platform.mat-home:/opt/mat}")
    private String matHome;

    // ── Heap Dump ────────────────────────────────────────────────────────

    /**
     * Triggers a heap dump from the target service via JMX.
     * Runs Eclipse MAT headless analysis and returns the path to the HTML report.
     */
    public String collectAndAnalyzeHeapDump(String runId,
            TeamConfig.TargetConfig target,
            TeamConfig.HeapDumpConfig heapConfig) {
        Path runDir = Paths.get(workDir, runId, "heap-dump");
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            log.error("[{}] Cannot create heap dump directory", runId, e);
            return null;
        }

        Path dumpFile = runDir.resolve("heap.hprof");
        log.info("[{}] Triggering heap dump on {}:{} via JMX", runId, target.getJvmHost(), target.getJvmJmxPort());

        try {
            triggerHeapDumpViaJmx(target.getJvmHost(), target.getJvmJmxPort(), dumpFile.toString());
            log.info("[{}] Heap dump captured: {}", runId, dumpFile);
        } catch (Exception e) {
            log.error("[{}] Failed to capture heap dump via JMX", runId, e);
            return null;
        }

        // Run Eclipse MAT headless analysis
        String analysisPath = runMatAnalysis(runId, dumpFile, runDir);
        return (analysisPath != null) ? analysisPath : dumpFile.toString();
    }

    private void triggerHeapDumpViaJmx(String host, int port, String dumpPath) throws Exception {
        String url = String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi", host, port);
        JMXServiceURL serviceUrl = new JMXServiceURL(url);

        try (JMXConnector connector = JMXConnectorFactory.connect(serviceUrl)) {
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            ObjectName hotspotBean = new ObjectName(HOTSPOT_BEAN);
            mbsc.invoke(hotspotBean, "dumpHeap",
                    new Object[] { dumpPath, true },
                    new String[] { "java.lang.String", "boolean" });
        }
    }

    private String runMatAnalysis(String runId, Path dumpFile, Path outputDir) {
        String matBin = Paths.get(matHome, "ParseHeapDump.sh").toString();
        Path reportDir = outputDir.resolve("mat-report");

        List<String> cmd = List.of(
                matBin,
                dumpFile.toString(),
                "-report",
                "org.eclipse.mat.api:suspects,org.eclipse.mat.api:overview,org.eclipse.mat.api:top_components",
                "-d", reportDir.toString());

        log.info("[{}] Running Eclipse MAT: {}", runId, String.join(" ", cmd));
        try {
            Path logFile = outputDir.resolve("mat.log");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile.toFile());

            Process process = pb.start();
            int exit = process.waitFor();

            if (exit == 0) {
                log.info("[{}] MAT analysis complete in: {}", runId, reportDir);
                return reportDir.toString();
            } else {
                log.warn("[{}] MAT exited with code {}. Check: {}", runId, exit, logFile);
                return null;
            }
        } catch (Exception e) {
            log.error("[{}] Eclipse MAT execution failed", runId, e);
            return null;
        }
    }

    // ── Thread Dump ──────────────────────────────────────────────────────

    /**
     * Collects thread dumps from the target service via JMX ThreadMXBean.
     * Detects deadlocks and generates a structured text report.
     */
    public String collectAndAnalyzeThreadDump(String runId, TeamConfig.TargetConfig target) {
        Path runDir = Paths.get(workDir, runId, "thread-dump");
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            log.error("[{}] Cannot create thread dump directory", runId, e);
            return null;
        }

        log.info("[{}] Collecting thread dump from {}:{} via JMX", runId, target.getJvmHost(), target.getJvmJmxPort());
        String url = String.format("service:jmx:rmi:///jndi/rmi://%s:%d/jmxrmi",
                target.getJvmHost(), target.getJvmJmxPort());

        try {
            JMXServiceURL serviceUrl = new JMXServiceURL(url);
            try (JMXConnector connector = JMXConnectorFactory.connect(serviceUrl)) {
                MBeanServerConnection mbsc = connector.getMBeanServerConnection();

                // Use ThreadMXBean via JMX
                ObjectName threadBean = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
                long[] threadIds = (long[]) mbsc.getAttribute(threadBean, "AllThreadIds");

                // Collect composite data for each thread
                String report = generateThreadReport(mbsc, threadBean, threadIds, runId);
                Path reportFile = runDir.resolve("thread-dump-" + Instant.now().getEpochSecond() + ".txt");
                Files.writeString(reportFile, report);
                log.info("[{}] Thread dump report written: {}", runId, reportFile);
                return reportFile.toString();
            }
        } catch (Exception e) {
            log.error("[{}] Failed to collect thread dump via JMX", runId, e);
            return null;
        }
    }

    private String generateThreadReport(MBeanServerConnection mbsc, ObjectName threadBean,
            long[] threadIds, String runId) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Thread Dump Report — Run: ").append(runId).append(" ").append(Instant.now()).append(" ===\n\n");

        // Check for deadlocks
        long[] deadlockedIds = (long[]) mbsc.invoke(threadBean, "findDeadlockedThreads", null, null);
        if (deadlockedIds != null && deadlockedIds.length > 0) {
            sb.append("⚠️  DEADLOCK DETECTED — ").append(deadlockedIds.length).append(" thread(s) deadlocked!\n\n");
        } else {
            sb.append("✅ No deadlocks detected.\n\n");
        }

        // Thread state summary
        sb.append("--- Thread State Summary ---\n");
        int[] stateCounts = new int[Thread.State.values().length];

        for (long id : threadIds) {
            Object[] params = { new long[] { id }, true, true };
            String[] sigs = { "[J", "boolean", "boolean" };
            Object[] infos = (Object[]) mbsc.invoke(threadBean, "getThreadInfo", params, sigs);
            if (infos != null && infos.length > 0 && infos[0] != null) {
                CompositeData cd = (CompositeData) infos[0];
                String state = cd.get("threadState").toString();
                String name = cd.get("threadName").toString();
                sb.append(String.format("  [%-13s] %s%n", state, name));
            }
        }

        sb.append("\nTotal threads: ").append(threadIds.length).append("\n");
        return sb.toString();
    }
}
