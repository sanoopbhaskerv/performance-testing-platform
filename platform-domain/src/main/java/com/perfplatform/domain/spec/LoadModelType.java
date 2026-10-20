package com.perfplatform.domain.spec;

import com.perfplatform.domain.spec.support.SpecEnums;

/**
 * How a protocol workload's load is shaped over time.
 *
 * <p>Two shapes, and the difference between them is not cosmetic — it decides what a run is capable
 * of measuring.
 *
 * <h2>{@link #STAGES}: a closed model</h2>
 * A fixed number of virtual users, each sending a request, waiting for the answer, and sending the
 * next. Throughput is therefore an <em>output</em>: if the system slows down, the users send less,
 * and the load applied falls with it. That is a faithful model of a fixed population of people using
 * a system, and it is what most load tests want.
 *
 * <p>It also has a well-known blind spot, usually called coordinated omission: a system that stalls
 * receives <em>less</em> traffic during the stall, so the test under-reports how bad the stall was.
 *
 * <h2>{@link #ARRIVAL_RATE}: an open model</h2>
 * Requests arrive at a stated rate whatever the system does with them. Throughput is an
 * <em>input</em>. This is a faithful model of traffic the system does not control — an upstream
 * queue, a scheduled batch, a public endpoint — and it is the only way to ask "can it sustain 500
 * requests a second?" rather than "what does it do with 200 users?".
 *
 * <p>It is also the model that can destroy a generator. If the target stalls, in-flight requests
 * accumulate without bound: the arrival rate keeps producing them and nothing is completing. The
 * generator exhausts its own memory or threads, dies, and reports its own failure as the target's.
 * That is why a specification using this model must declare a concurrency ceiling, and why admission
 * control budgets against that ceiling rather than against the rate.
 *
 * <p>Load, stress, spike and endurance tests are all expressible as a staged shape in either model,
 * so the platform models the shape rather than naming fixed test types — which is what forced V1 to
 * keep three near-duplicate JMX templates in step.
 *
 * <p>See {@code docs/operations/load-models.md}.
 */
public enum LoadModelType {

    /** Concurrent users. Throughput is what the system permits. */
    STAGES,

    /** Requests per second. Concurrency is what the system's latency produces, up to a ceiling. */
    ARRIVAL_RATE;

    public static LoadModelType from(String raw) {
        return SpecEnums.parse(LoadModelType.class, raw, "load model type");
    }

    /** Whether load is applied regardless of what the system does with it. */
    public boolean isOpen() {
        return this == ARRIVAL_RATE;
    }
}
