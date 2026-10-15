package com.archnode.telemetry;

import java.time.Instant;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** Lock-free counters feeding the dashboard's alert/metrics panel. */
@Component
public class TelemetryMetrics {

    public final LongAdder received = new LongAdder();
    public final LongAdder errors = new LongAdder();
    public final LongAdder malformed = new LongAdder();
    public final LongAdder alerts = new LongAdder();
    public final LongAdder cacheHits = new LongAdder();
    public final LongAdder llmCalls = new LongAdder();
    public final LongAdder llmFailures = new LongAdder();
    public final LongAdder tokensSpent = new LongAdder();
    public final LongAdder tokensSaved = new LongAdder();

    public record Snapshot(
            Instant at, long received, long errors, long malformed, long alerts,
            long cacheHits, long llmCalls, long llmFailures,
            long tokensSpent, long tokensSaved, double cacheHitRatio) {
    }

    public Snapshot snapshot() {
        long hits = cacheHits.sum();
        long calls = llmCalls.sum();
        double ratio = hits + calls == 0 ? 0.0 : (double) hits / (hits + calls);
        return new Snapshot(Instant.now(), received.sum(), errors.sum(), malformed.sum(), alerts.sum(),
                hits, calls, llmFailures.sum(), tokensSpent.sum(), tokensSaved.sum(), ratio);
    }
}
