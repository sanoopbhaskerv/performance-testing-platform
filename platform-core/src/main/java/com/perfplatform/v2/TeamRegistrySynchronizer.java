package com.perfplatform.v2;

import com.perfplatform.config.TeamConfig;
import com.perfplatform.config.TeamConfigLoader;
import com.perfplatform.domain.identity.ApplicationId;
import com.perfplatform.domain.identity.TeamId;
import com.perfplatform.domain.port.TargetRegistry;
import com.perfplatform.domain.port.TeamProvisioning;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.domain.target.TargetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Mirrors V1's team YAML into the V2 registry at startup.
 *
 * <p>A migration adapter, and deliberately nothing more. During the V1/V2 overlap the team YAML in
 * {@code config/teams/} is already the declarative source of truth for which teams exist and which
 * applications they own; this reads it and makes sure the registry agrees, so a team that can use V1
 * can also author a V2 definition without a separate onboarding step.
 *
 * <p>It closes the gap Increment 1C left open — V2 definitions must reference a registered team and
 * application, and nothing created them. The alternatives were an admin API that no caller currently
 * holds the scope to use, or auto-provisioning on first use, where a typo in
 * {@code metadata.team} silently manufactures a team with no owner, no quota and no approval policy.
 * Reading an existing reviewed file is the option that creates nothing nobody declared.
 *
 * <h2>Not for production</h2>
 * Restricted to non-production profiles for the same reason as the fake execution provider and the
 * development caller identity: writing business data from a config file at boot is acceptable while
 * that file <em>is</em> the source of truth, and stops being acceptable the moment corporate identity
 * and a real onboarding process exist (Increment 4).
 *
 * <h2>What it does not do</h2>
 * It never deletes, renames or reclassifies. A team removed from YAML keeps its registry row, its
 * definitions and its run history — those are records of things that happened, and a config edit is
 * not a reason to lose them. An application's target class is applied on creation only: it governs
 * which targets may be tested and whether approval is required, so a YAML edit must not be able to
 * quietly downgrade a production application to non-production.
 */
public class TeamRegistrySynchronizer {

    private static final Logger log = LoggerFactory.getLogger(TeamRegistrySynchronizer.class);

    private final TeamConfigLoader teamConfigs;
    private final TeamProvisioning provisioning;
    private final TargetRegistry targets;

    public TeamRegistrySynchronizer(TeamConfigLoader teamConfigs, TeamProvisioning provisioning,
            TargetRegistry targets) {
        this.teamConfigs = teamConfigs;
        this.provisioning = provisioning;
        this.targets = targets;
    }

    /**
     * Runs once the application is up.
     *
     * <p>On {@link ApplicationReadyEvent} rather than {@code @PostConstruct} so that Flyway has
     * finished and the schema exists. A failure here is logged and swallowed per team: an
     * unparseable team file must not stop the platform from starting for every other team.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void synchronize() {
        int teamsSeen = 0;
        int applicationsSeen = 0;

        for (TeamConfig config : teamConfigs.getAllConfigs()) {
            try {
                TeamId teamId = provisioning.ensureTeam(config.getTeam());
                teamsSeen++;

                for (TeamConfig.TargetConfig target : config.getTargets()) {
                    // Everything arriving from V1 YAML is non-production. A production target is a
                    // policy decision with an approval requirement attached, and it is not one a
                    // team makes by editing their own config file.
                    ApplicationId applicationId = provisioning.ensureApplication(teamId, target.getName(),
                            TargetClass.NON_PRODUCTION);
                    if (target.getBaseUrl() != null && !target.getBaseUrl().isBlank()) {
                        targets.register(teamId, applicationId, target.getName(), "performance",
                                TargetClass.NON_PRODUCTION, TargetAddress.parse(target.getBaseUrl()),
                                "yaml-sync");
                    }
                    applicationsSeen++;
                }
            } catch (RuntimeException e) {
                log.error("Could not synchronize team '{}' into the V2 registry; "
                                + "V2 authoring for it will be refused until this is fixed",
                        config.getTeam(), e);
            }
        }

        log.info("Team registry synchronized from YAML: teams={} applications={}",
                teamsSeen, applicationsSeen);
    }
}
