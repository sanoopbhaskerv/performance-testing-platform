package com.perfplatform.v2;

import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TargetDirectory;
import com.perfplatform.domain.port.TargetResolver;
import com.perfplatform.domain.target.TargetAddress;
import com.perfplatform.domain.target.TargetPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Resolves a run's target from V1's team YAML.
 *
 * <p>A migration adapter, like {@link TeamRegistrySynchronizer}. Team YAML already declares each
 * team's targets and their base URLs, so during the V1/V2 overlap it is the honest source: a team
 * that can point V1 at a service can point V2 at the same one, with no second place to keep in step.
 *
 * <p>The specification never supplies the target. A document that named its own would route around
 * whatever allowlist it was validated against, so the address is resolved here, from configuration a
 * team cannot change per-run, and checked against {@link TargetPolicy} before it is returned.
 *
 * <h2>Not for production</h2>
 * Restricted to non-production profiles. A production deployment needs a target registry with
 * environment classification and a hostname allowlist — CLAUDE.md §20 — and enabling production or
 * unrestricted targets is a pause condition (§31). This resolver deliberately cannot express any of
 * that: every target it returns is treated as non-production.
 */
public class TeamYamlTargetResolver implements TargetResolver {

    private static final Logger log = LoggerFactory.getLogger(TeamYamlTargetResolver.class);

    private final TeamConfigLoader teamConfigs;
    private final TargetDirectory teams;

    public TeamYamlTargetResolver(TeamConfigLoader teamConfigs, TargetDirectory teams) {
        this.teamConfigs = teamConfigs;
        this.teams = teams;
    }

    @Override
    public Optional<TargetAddress> resolve(TeamId teamId, String applicationName, String environment) {
        Optional<String> teamName = teams.teamName(teamId);
        if (teamName.isEmpty()) {
            log.warn("Cannot resolve a target: team {} is not in the registry", teamId);
            return Optional.empty();
        }

        Optional<TeamConfig> config = teamConfigs.getConfig(teamName.get());
        if (config.isEmpty()) {
            log.warn("Cannot resolve a target for application '{}': team '{}' has no YAML "
                    + "configuration", applicationName, teamName.get());
            return Optional.empty();
        }

        // An application maps to the team target of the same name — which is exactly how
        // TeamRegistrySynchronizer created the applications in the first place.
        Optional<String> baseUrl = config.get().getTargets().stream()
                .filter(target -> applicationName != null
                        && applicationName.equalsIgnoreCase(target.getName()))
                .map(TeamConfig.TargetConfig::getBaseUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst();

        if (baseUrl.isEmpty()) {
            log.warn("Team '{}' declares no target named '{}' with a base URL, so a run against it "
                    + "cannot be pointed anywhere", teamName.get(), applicationName);
            return Optional.empty();
        }

        TargetAddress address;
        try {
            address = TargetAddress.parse(baseUrl.get());
        } catch (IllegalArgumentException e) {
            log.error("Team '{}' target '{}' has an unusable base URL", teamName.get(),
                    applicationName, e);
            return Optional.empty();
        }

        // Checked before it is handed to an engine, not after. Returning an address the policy
        // forbids and relying on a later check would put a permitted-target decision in two places.
        Optional<TargetPolicy.Refusal> refusal = TargetPolicy.check(address);
        if (refusal.isPresent()) {
            log.error("Refusing target {} for team '{}' application '{}': {}",
                    address.baseUrl(), teamName.get(), applicationName, refusal.get().reason());
            return Optional.empty();
        }

        return Optional.of(address);
    }
}
