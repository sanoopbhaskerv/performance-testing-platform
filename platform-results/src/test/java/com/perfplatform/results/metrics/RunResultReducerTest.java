package com.perfplatform.results.metrics;

import com.perfplatform.domain.identity.TestRunId;
import com.perfplatform.domain.results.Sample;
import com.perfplatform.domain.results.SampleSet;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RunResultReducerTest {
    @Test
    void secondaryTimingsAndScoresDoNotDiluteJourneyResultsOrExtendItsWindow() {
        var result = RunResultReducer.reduce(TestRunId.generate(), "playwright", 1, 1,
                new SampleSet(List.of(new Sample("checkout", 1000, false, 10000),
                        new Sample("checkout/action", 20, true, 10000, false),
                        new Sample("web.lcp@checkout", 9000, true, 10500, false),
                        new Sample("web.cls@checkout", 95, true, 10500, false)), 0));
        assertThat(result.overall().sampleCount()).isEqualTo(1);
        assertThat(result.overall().errorCount()).isEqualTo(1);
        assertThat(result.overall().p95Millis()).isEqualTo(1000);
        assertThat(result.overall().throughputPerSecondMilli()).isEqualTo(1000);
        assertThat(result.byOperation()).containsKeys("checkout/action", "web.cls@checkout");
    }

    @Test
    void secondaryEvidenceAloneCannotLookLikeSuccessfulJourneys() {
        var result = RunResultReducer.reduce(TestRunId.generate(), "playwright", 1, 1,
                new SampleSet(List.of(new Sample("web.cls@checkout", 0, true, 10000, false)), 0));
        assertThat(result.overall().sampleCount()).isZero();
        assertThat(result.byOperation()).containsKey("web.cls@checkout");
    }
}
