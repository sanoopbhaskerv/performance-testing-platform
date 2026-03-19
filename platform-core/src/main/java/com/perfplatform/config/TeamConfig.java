package com.perfplatform.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a team's performance test configuration.
 * Loaded from config/teams/<team-name>.yml
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeamConfig {

    private String team;
    private String apiKey;
    private List<TargetConfig> targets = new ArrayList<>();
    private TestsConfig tests = new TestsConfig();
    private SlaConfig sla = new SlaConfig();
    private AnalysisConfig analysis = new AnalysisConfig();
    private NotificationsConfig notifications = new NotificationsConfig();

    // ── Getters / Setters ─────────────────────────────────────────────────

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public List<TargetConfig> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetConfig> targets) {
        this.targets = targets;
    }

    public TestsConfig getTests() {
        return tests;
    }

    public void setTests(TestsConfig tests) {
        this.tests = tests;
    }

    public SlaConfig getSla() {
        return sla;
    }

    public void setSla(SlaConfig sla) {
        this.sla = sla;
    }

    public AnalysisConfig getAnalysis() {
        return analysis;
    }

    public void setAnalysis(AnalysisConfig analysis) {
        this.analysis = analysis;
    }

    public NotificationsConfig getNotifications() {
        return notifications;
    }

    public void setNotifications(NotificationsConfig notifications) {
        this.notifications = notifications;
    }

    // ── Nested POJOs ─────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetConfig {
        private String name;
        private String baseUrl;
        private String healthCheckPath = "/health";
        private boolean jvmEnabled = false;
        private String jvmHost;
        private int jvmJmxPort = 9010;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getHealthCheckPath() {
            return healthCheckPath;
        }

        public void setHealthCheckPath(String healthCheckPath) {
            this.healthCheckPath = healthCheckPath;
        }

        public boolean isJvmEnabled() {
            return jvmEnabled;
        }

        public void setJvmEnabled(boolean jvmEnabled) {
            this.jvmEnabled = jvmEnabled;
        }

        public String getJvmHost() {
            return jvmHost;
        }

        public void setJvmHost(String jvmHost) {
            this.jvmHost = jvmHost;
        }

        public int getJvmJmxPort() {
            return jvmJmxPort;
        }

        public void setJvmJmxPort(int jvmJmxPort) {
            this.jvmJmxPort = jvmJmxPort;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TestsConfig {
        private LoadTestConfig load = new LoadTestConfig();
        private EnduranceTestConfig endurance = new EnduranceTestConfig();
        private SpikeTestConfig spike = new SpikeTestConfig();

        public LoadTestConfig getLoad() {
            return load;
        }

        public void setLoad(LoadTestConfig load) {
            this.load = load;
        }

        public EnduranceTestConfig getEndurance() {
            return endurance;
        }

        public void setEndurance(EnduranceTestConfig endurance) {
            this.endurance = endurance;
        }

        public SpikeTestConfig getSpike() {
            return spike;
        }

        public void setSpike(SpikeTestConfig spike) {
            this.spike = spike;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoadTestConfig {
        private boolean enabled = false;
        private int rampUpUsers = 10;
        private int steadyStateUsers = 50;
        private int rampUpDurationSeconds = 60;
        private int steadyStateDurationSeconds = 300;
        private List<ScenarioConfig> scenarios = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRampUpUsers() {
            return rampUpUsers;
        }

        public void setRampUpUsers(int rampUpUsers) {
            this.rampUpUsers = rampUpUsers;
        }

        public int getSteadyStateUsers() {
            return steadyStateUsers;
        }

        public void setSteadyStateUsers(int steadyStateUsers) {
            this.steadyStateUsers = steadyStateUsers;
        }

        public int getRampUpDurationSeconds() {
            return rampUpDurationSeconds;
        }

        public void setRampUpDurationSeconds(int rampUpDurationSeconds) {
            this.rampUpDurationSeconds = rampUpDurationSeconds;
        }

        public int getSteadyStateDurationSeconds() {
            return steadyStateDurationSeconds;
        }

        public void setSteadyStateDurationSeconds(int steadyStateDurationSeconds) {
            this.steadyStateDurationSeconds = steadyStateDurationSeconds;
        }

        public List<ScenarioConfig> getScenarios() {
            return scenarios;
        }

        public void setScenarios(List<ScenarioConfig> scenarios) {
            this.scenarios = scenarios;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnduranceTestConfig {
        private boolean enabled = false;
        private int users = 20;
        private int durationMinutes = 60;
        private int memoryLeakThresholdMB = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getUsers() {
            return users;
        }

        public void setUsers(int users) {
            this.users = users;
        }

        public int getDurationMinutes() {
            return durationMinutes;
        }

        public void setDurationMinutes(int durationMinutes) {
            this.durationMinutes = durationMinutes;
        }

        public int getMemoryLeakThresholdMB() {
            return memoryLeakThresholdMB;
        }

        public void setMemoryLeakThresholdMB(int memoryLeakThresholdMB) {
            this.memoryLeakThresholdMB = memoryLeakThresholdMB;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpikeTestConfig {
        private boolean enabled = false;
        private int peakUsers = 200;
        private int spikeDurationSeconds = 30;
        private int baseUsers = 10;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPeakUsers() {
            return peakUsers;
        }

        public void setPeakUsers(int peakUsers) {
            this.peakUsers = peakUsers;
        }

        public int getSpikeDurationSeconds() {
            return spikeDurationSeconds;
        }

        public void setSpikeDurationSeconds(int spikeDurationSeconds) {
            this.spikeDurationSeconds = spikeDurationSeconds;
        }

        public int getBaseUsers() {
            return baseUsers;
        }

        public void setBaseUsers(int baseUsers) {
            this.baseUsers = baseUsers;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScenarioConfig {
        private String name;
        private List<EndpointConfig> endpoints = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<EndpointConfig> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(List<EndpointConfig> endpoints) {
            this.endpoints = endpoints;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointConfig {
        private String method = "GET";
        private String path;
        private Map<String, String> headers;
        private String body;

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SlaConfig {
        private int maxP95LatencyMs = 1000;
        private double maxErrorRatePercent = 1.0;
        private int maxHeapUsagePercent = 80;

        public int getMaxP95LatencyMs() {
            return maxP95LatencyMs;
        }

        public void setMaxP95LatencyMs(int maxP95LatencyMs) {
            this.maxP95LatencyMs = maxP95LatencyMs;
        }

        public double getMaxErrorRatePercent() {
            return maxErrorRatePercent;
        }

        public void setMaxErrorRatePercent(double maxErrorRatePercent) {
            this.maxErrorRatePercent = maxErrorRatePercent;
        }

        public int getMaxHeapUsagePercent() {
            return maxHeapUsagePercent;
        }

        public void setMaxHeapUsagePercent(int maxHeapUsagePercent) {
            this.maxHeapUsagePercent = maxHeapUsagePercent;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnalysisConfig {
        private HeapDumpConfig heapDump = new HeapDumpConfig();
        private ThreadDumpConfig threadDump = new ThreadDumpConfig();

        public HeapDumpConfig getHeapDump() {
            return heapDump;
        }

        public void setHeapDump(HeapDumpConfig heapDump) {
            this.heapDump = heapDump;
        }

        public ThreadDumpConfig getThreadDump() {
            return threadDump;
        }

        public void setThreadDump(ThreadDumpConfig threadDump) {
            this.threadDump = threadDump;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HeapDumpConfig {
        private boolean enabled = false; // OFF by default — opt-in for JVM services
        private boolean triggerOnOOM = true;
        private int triggerOnHeapPercent = 85;
        private boolean manualTrigger = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isTriggerOnOOM() {
            return triggerOnOOM;
        }

        public void setTriggerOnOOM(boolean triggerOnOOM) {
            this.triggerOnOOM = triggerOnOOM;
        }

        public int getTriggerOnHeapPercent() {
            return triggerOnHeapPercent;
        }

        public void setTriggerOnHeapPercent(int triggerOnHeapPercent) {
            this.triggerOnHeapPercent = triggerOnHeapPercent;
        }

        public boolean isManualTrigger() {
            return manualTrigger;
        }

        public void setManualTrigger(boolean manualTrigger) {
            this.manualTrigger = manualTrigger;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ThreadDumpConfig {
        private boolean enabled = false; // OFF by default — opt-in for JVM services
        private int intervalSeconds = 30;
        private boolean captureOnDeadlock = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public boolean isCaptureOnDeadlock() {
            return captureOnDeadlock;
        }

        public void setCaptureOnDeadlock(boolean captureOnDeadlock) {
            this.captureOnDeadlock = captureOnDeadlock;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationsConfig {
        private SlackConfig slack = new SlackConfig();
        private EmailConfig email = new EmailConfig();

        public SlackConfig getSlack() {
            return slack;
        }

        public void setSlack(SlackConfig slack) {
            this.slack = slack;
        }

        public EmailConfig getEmail() {
            return email;
        }

        public void setEmail(EmailConfig email) {
            this.email = email;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SlackConfig {
        private boolean enabled = false;
        private String webhookUrl;
        private List<String> notifyOn = List.of("COMPLETED", "FAILED", "SLA_BREACH");

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public List<String> getNotifyOn() {
            return notifyOn;
        }

        public void setNotifyOn(List<String> notifyOn) {
            this.notifyOn = notifyOn;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailConfig {
        private boolean enabled = false;
        private List<String> recipients = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getRecipients() {
            return recipients;
        }

        public void setRecipients(List<String> recipients) {
            this.recipients = recipients;
        }
    }
}
