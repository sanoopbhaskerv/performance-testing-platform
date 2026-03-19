package com.perfplatform.notification;

import com.perfplatform.config.TeamConfig.NotificationsConfig;
import com.perfplatform.storage.TestRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Sends Slack and email notifications on test completion, failure, or SLA
 * breach.
 * Each channel is independently configurable and defaults to off.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JavaMailSender mailSender;
    private final RestTemplate restTemplate = new RestTemplate();

    public NotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void notify(TestRun run, NotificationsConfig config) {
        String event = resolveEvent(run);

        if (config.getSlack().isEnabled() && config.getSlack().getNotifyOn().contains(event)) {
            sendSlack(run, config.getSlack().getWebhookUrl(), event);
        }
        if (config.getEmail().isEnabled() && !config.getEmail().getRecipients().isEmpty()) {
            sendEmail(run, config.getEmail().getRecipients(), event);
        }
    }

    private void sendSlack(TestRun run, String webhookUrl, String event) {
        try {
            String emoji = run.getStatus() == TestRun.Status.COMPLETED ? "✅" : "❌";
            String sla = run.getSlaResult() == TestRun.SlaResult.BREACH ? " 🚨 *SLA BREACHED*" : "";
            Duration duration = run.getStartedAt() != null && run.getCompletedAt() != null
                    ? Duration.between(run.getStartedAt(), run.getCompletedAt())
                    : Duration.ZERO;

            String text = String.format(
                    "%s *Performance Test %s* — Team: `%s` | Type: `%s` | Duration: %dm%ds%s\n" +
                            "P95 Latency: `%sms` | Error Rate: `%.2f%%` | Run ID: `%s`",
                    emoji, run.getStatus(), run.getTeam(), run.getTestType(),
                    duration.toMinutes(), duration.toSecondsPart(), sla,
                    run.getP95LatencyMs() != null ? run.getP95LatencyMs() : "N/A",
                    run.getErrorRatePercent() != null ? run.getErrorRatePercent() : 0.0,
                    run.getId());

            restTemplate.postForEntity(webhookUrl, Map.of("text", text), String.class);
            log.info("Slack notification sent for run {}", run.getId());
        } catch (Exception e) {
            log.error("Failed to send Slack notification for run {}", run.getId(), e);
        }
    }

    private void sendEmail(TestRun run, java.util.List<String> recipients, String event) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(recipients.toArray(String[]::new));
            msg.setSubject(String.format("[PerfPlatform] %s — %s test %s for team %s",
                    event, run.getTestType(), run.getStatus(), run.getTeam()));
            msg.setText(String.format(
                    "Test Run ID: %s\nTeam: %s\nType: %s\nStatus: %s\nSLA Result: %s\n" +
                            "P95 Latency: %s ms\nError Rate: %.2f%%\nFailure Reason: %s",
                    run.getId(), run.getTeam(), run.getTestType(), run.getStatus(), run.getSlaResult(),
                    run.getP95LatencyMs() != null ? run.getP95LatencyMs() : "N/A",
                    run.getErrorRatePercent() != null ? run.getErrorRatePercent() : 0.0,
                    run.getFailureReason() != null ? run.getFailureReason() : "None"));
            mailSender.send(msg);
            log.info("Email notification sent for run {}", run.getId());
        } catch (Exception e) {
            log.error("Failed to send email notification for run {}", run.getId(), e);
        }
    }

    private String resolveEvent(TestRun run) {
        if (run.getStatus() == TestRun.Status.FAILED)
            return "FAILED";
        if (run.getSlaResult() == TestRun.SlaResult.BREACH)
            return "SLA_BREACH";
        return "COMPLETED";
    }
}
