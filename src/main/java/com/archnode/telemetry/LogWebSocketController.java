package com.archnode.telemetry;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

/**
 * STOMP endpoint /ws. Dashboard subscriptions:
 * /topic/logs (live feed), /topic/alerts (diagnosed incidents), /topic/metrics (counters, every 2s).
 * A client may also send to /app/metrics to get an immediate snapshot on /topic/metrics.
 */
@Controller
public class LogWebSocketController {

    /** Cap on non-error events pushed to browsers per second; errors always pass. */
    static final int MAX_LIVE_EVENTS_PER_SECOND = 200;
    private static final long METRICS_INTERVAL_MS = 2000;

    public record MetricsMessage(TelemetryMetrics.Snapshot totals, double logsPerSecond) {
    }

    private final SimpMessagingTemplate template;
    private final TelemetryMetrics metrics;
    private final LogPayloadReducer reducer;
    private final AtomicInteger livePermits = new AtomicInteger(MAX_LIVE_EVENTS_PER_SECOND);
    private volatile long lastReceived;
    private volatile long lastTickNanos = System.nanoTime();

    public LogWebSocketController(SimpMessagingTemplate template, TelemetryMetrics metrics,
                                  LogPayloadReducer reducer) {
        this.template = template;
        this.metrics = metrics;
        this.reducer = reducer;
    }

    /** Pushes one event to the live feed (stack traces trimmed; non-errors rate limited). */
    public void broadcastLog(LogEvent event) {
        if (!event.isError() && livePermits.getAndDecrement() <= 0) {
            return;
        }
        template.convertAndSend("/topic/logs", event.withStackTrace(reducer.truncateStackTrace(event.stackTrace())));
    }

    public void broadcastAlert(LogAnalysisEngine.Incident incident) {
        template.convertAndSend("/topic/alerts", incident);
    }

    @MessageMapping("/metrics")
    @SendTo("/topic/metrics")
    public MetricsMessage currentMetrics() {
        return new MetricsMessage(metrics.snapshot(), logsPerSecond());
    }

    @Scheduled(fixedRate = 1000)
    void refillLivePermits() {
        livePermits.set(MAX_LIVE_EVENTS_PER_SECOND);
    }

    @Scheduled(fixedRate = METRICS_INTERVAL_MS)
    void publishMetrics() {
        template.convertAndSend("/topic/metrics", currentMetrics());
    }

    private synchronized double logsPerSecond() {
        long now = System.nanoTime();
        long received = metrics.received.sum();
        double seconds = (now - lastTickNanos) / 1_000_000_000.0;
        double rate = seconds > 0 ? (received - lastReceived) / seconds : 0.0;
        lastReceived = received;
        lastTickNanos = now;
        return rate;
    }
}
