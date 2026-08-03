package com.perfplatform.runner.kubernetes;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deletes runner-owned Secrets whose explicit lifecycle has elapsed.
 *
 * <p>The execution provider deletes ephemeral Secrets on normal terminal observations and
 * cancellation. This sweeper covers the failure mode where the control plane dies before observing
 * the Job again. It only acts on runner-managed Secrets that carry the delete-after annotation, so
 * regular service credentials and platform-owned cluster objects are outside its reach.
 */
public final class KubernetesEphemeralSecretCleaner implements EphemeralSecretCleaner {

    private static final Logger log =
            LoggerFactory.getLogger(KubernetesEphemeralSecretCleaner.class);

    static final String ANNOTATION_SECRET_EXPIRES_AT =
            "perfplatform.ameriprise.com/secret-expires-at";
    static final String ANNOTATION_SECRET_DELETE_AFTER =
            "perfplatform.ameriprise.com/secret-delete-after";

    private final KubernetesClient client;
    private final RunnerCellSettings settings;

    public KubernetesEphemeralSecretCleaner(KubernetesClient client, RunnerCellSettings settings) {
        this.client = Objects.requireNonNull(client, "client");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Deletes all expired runner-managed Secrets and returns how many delete requests were issued.
     */
    @Override
    public int deleteExpired(Instant now) {
        Objects.requireNonNull(now, "now");

        List<Secret> secrets;
        try {
            secrets = client.secrets()
                    .inNamespace(settings.namespace())
                    .withLabel(RunnerJobManifest.LABEL_MANAGED_BY, RunnerJobManifest.MANAGED_BY)
                    .list()
                    .getItems();
        } catch (KubernetesClientException e) {
            log.warn("Could not list runner Secrets for cleanup namespace={} status={}",
                    settings.namespace(), e.getCode());
            return 0;
        }

        int deleted = 0;
        for (Secret secret : secrets) {
            if (shouldDelete(secret, now) && delete(secret)) {
                deleted++;
            }
        }
        return deleted;
    }

    private static boolean shouldDelete(Secret secret, Instant now) {
        Map<String, String> annotations = secret.getMetadata() == null
                ? Map.of()
                : secret.getMetadata().getAnnotations();
        if (annotations == null || !annotations.containsKey(ANNOTATION_SECRET_DELETE_AFTER)) {
            return false;
        }

        try {
            Instant deleteAfter = Instant.parse(annotations.get(ANNOTATION_SECRET_DELETE_AFTER));
            return !deleteAfter.isAfter(now);
        } catch (RuntimeException e) {
            log.warn("Ignoring runner Secret with invalid delete-after annotation name={}",
                    secret.getMetadata() == null ? "unknown" : secret.getMetadata().getName());
            return false;
        }
    }

    private boolean delete(Secret secret) {
        String name = secret.getMetadata() == null ? null : secret.getMetadata().getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        try {
            client.secrets()
                    .inNamespace(settings.namespace())
                    .withName(name)
                    .delete();
            return true;
        } catch (KubernetesClientException e) {
            log.warn("Could not delete expired runner Secret name={} status={}", name, e.getCode());
            return false;
        }
    }
}
