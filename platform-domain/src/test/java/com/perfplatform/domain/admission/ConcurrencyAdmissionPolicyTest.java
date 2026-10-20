package com.perfplatform.domain.admission;

import com.perfplatform.domain.identity.TeamId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admission control, including the per-team fairness rule.
 *
 * <p>The interesting case is not the global ceiling but the per-team one: without it a single team
 * submitting ten runs occupies every global slot and nine other teams wait behind it, with the
 * platform technically inside its limits and useless to everyone else.
 */
class ConcurrencyAdmissionPolicyTest {

    private final AdmissionPolicy policy = new ConcurrencyAdmissionPolicy();

    private static final TeamId ADVICE = TeamId.generate();
    private static final TeamId BROKERAGE = TeamId.generate();

    @Nested
    @DisplayName("global ceiling")
    class Global {

        @Test
        @DisplayName("an idle platform admits")
        void admitsWhenIdle() {
            assertThat(policy.evaluate(ADVICE, CapacityLimits.DEFAULTS, CapacitySnapshot.EMPTY)
                    .admitted()).isTrue();
        }

        @Test
        @DisplayName("a full platform defers, and says the platform is full rather than blaming the team")
        void defersWhenFull() {
            // Ten other teams each running one: this team is within its own limit, so reporting a
            // team limit here would send them to look at their own runs for no reason.
            CapacitySnapshot full = new CapacitySnapshot(10, Map.of());

            AdmissionDecision decision = policy.evaluate(ADVICE, CapacityLimits.DEFAULTS, full);

            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.code()).isEqualTo(AdmissionDecision.GLOBAL_LIMIT);
            assertThat(decision.reason()).contains("10 concurrent runs");
        }

        @Test
        @DisplayName("the global ceiling is checked before the team ceiling")
        void globalTakesPrecedence() {
            // Both limits are breached; the platform-wide fact is the more useful one.
            CapacitySnapshot full = new CapacitySnapshot(10, Map.of(ADVICE, 1));

            assertThat(policy.evaluate(ADVICE, CapacityLimits.DEFAULTS, full).code())
                    .isEqualTo(AdmissionDecision.GLOBAL_LIMIT);
        }
    }

    @Nested
    @DisplayName("per-team fairness")
    class PerTeam {

        @Test
        @DisplayName("a team already at its limit defers even when the platform has room")
        void defersAtTeamLimit() {
            CapacitySnapshot snapshot = new CapacitySnapshot(1, Map.of(ADVICE, 1));

            AdmissionDecision decision = policy.evaluate(ADVICE, CapacityLimits.DEFAULTS, snapshot);

            assertThat(decision.isDeferred()).isTrue();
            assertThat(decision.code()).isEqualTo(AdmissionDecision.TEAM_LIMIT);
        }

        @Test
        @DisplayName("another team is unaffected by the first team's usage")
        void otherTeamsUnaffected() {
            CapacitySnapshot snapshot = new CapacitySnapshot(1, Map.of(ADVICE, 1));

            assertThat(policy.evaluate(BROKERAGE, CapacityLimits.DEFAULTS, snapshot).admitted())
                    .isTrue();
        }

        @Test
        @DisplayName("a policy-approved override raises one team's ceiling without raising others'")
        void overrideAppliesToOneTeam() {
            CapacityLimits limits = new CapacityLimits(10, 1, Map.of(ADVICE, 2));
            CapacitySnapshot oneEach = new CapacitySnapshot(2, Map.of(ADVICE, 1, BROKERAGE, 1));

            assertThat(policy.evaluate(ADVICE, limits, oneEach).admitted()).isTrue();
            assertThat(policy.evaluate(BROKERAGE, limits, oneEach).isDeferred()).isTrue();
        }

        /**
         * The scenario from CLAUDE.md §28: ten queued runs from one team against a platform that
         * allows ten globally but one per team. Exactly one starts.
         */
        @Test
        @DisplayName("ten queued runs from one team admit one, not ten")
        void oneTeamCannotFillThePlatform() {
            CapacitySnapshot snapshot = CapacitySnapshot.EMPTY;
            int admitted = 0;

            for (int i = 0; i < 10; i++) {
                AdmissionDecision decision =
                        policy.evaluate(ADVICE, CapacityLimits.DEFAULTS, snapshot);
                if (decision.admitted()) {
                    admitted++;
                    // Each provisional admission must be visible to the next candidate, or a pass
                    // would admit every queued run of the same team at once.
                    snapshot = snapshot.withAdmitted(ADVICE);
                }
            }

            assertThat(admitted).isEqualTo(1);
            assertThat(snapshot.globalActive()).isEqualTo(1);
        }

        @Test
        @DisplayName("ten teams with one run each fill the platform exactly")
        void tenTeamsFillThePlatform() {
            CapacitySnapshot snapshot = CapacitySnapshot.EMPTY;
            int admitted = 0;

            for (int i = 0; i < 12; i++) {
                TeamId team = TeamId.generate();
                if (policy.evaluate(team, CapacityLimits.DEFAULTS, snapshot).admitted()) {
                    admitted++;
                    snapshot = snapshot.withAdmitted(team);
                }
            }

            assertThat(admitted).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("limit configuration")
    class Limits {

        @Test
        @DisplayName("the defaults are the documented ceilings")
        void defaults() {
            assertThat(CapacityLimits.DEFAULTS.globalMaxRuns()).isEqualTo(10);
            assertThat(CapacityLimits.DEFAULTS.defaultTeamMaxRuns()).isEqualTo(1);
        }

        @Test
        @DisplayName("a team ceiling above the global ceiling is rejected as configuration")
        void teamCannotExceedGlobal() {
            assertThatThrownBy(() -> new CapacityLimits(10, 1, Map.of(ADVICE, 11)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed globalMaxRuns");

            assertThatThrownBy(() -> new CapacityLimits(4, 5, Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed globalMaxRuns");
        }

        @Test
        @DisplayName("a team with no override uses the default")
        void defaultApplies() {
            assertThat(new CapacityLimits(10, 1, Map.of(ADVICE, 2)).limitFor(BROKERAGE)).isEqualTo(1);
        }
    }
}
