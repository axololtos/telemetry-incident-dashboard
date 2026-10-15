package com.archnode.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Consumes raw JSON logs. Normal logs stay entirely local (0 LLM tokens): they are counted and, rate limited,
 * streamed to the dashboard. Errors feed a per-service sliding window; only a burst crossing the threshold
 * is handed to {@link LogAnalysisEngine} on a virtual thread.
 */
@Service
public class KafkaLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaLogConsumer.class);

    private record Stamped(long arrivalMs, LogEvent event) {
    }

    /** Errors seen in the last window for one service. Guarded by its own monitor. */
    static final class ErrorWindow {
        private final ArrayDeque<Stamped> errors = new ArrayDeque<>();
        private long lastAlertMs = Long.MIN_VALUE / 2;

        /** Records an error; returns the burst to analyse if the threshold is crossed (and cooldown elapsed), else empty. */
        synchronized List<LogEvent> record(LogEvent event, long nowMs, long windowMs, int threshold,
                                           long cooldownMs, int maxBuffered) {
            errors.addLast(new Stamped(nowMs, event));
            while (!errors.isEmpty() && nowMs - errors.peekFirst().arrivalMs() > windowMs) {
                errors.pollFirst();
            }
            while (errors.size() > maxBuffered) {
                errors.pollFirst();
            }
            if (errors.size() < threshold || nowMs - lastAlertMs < cooldownMs) {
                return List.of();
            }
            lastAlertMs = nowMs;
            return errors.stream().map(Stamped::event).toList();
        }
    }

    private final ConcurrentHashMap<String, ErrorWindow> windows = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final LogAnalysisEngine engine;
    private final LogWebSocketController webSocket;
    private final TelemetryMetrics metrics;
    private final ExecutorService analysisExecutor;
    private final long windowMs;
    private final long cooldownMs;
    private final int threshold;
    private final int maxBuffered;

    public KafkaLogConsumer(ObjectMapper objectMapper, LogAnalysisEngine engine, LogWebSocketController webSocket,
                            TelemetryMetrics metrics, @Qualifier("analysisExecutor") ExecutorService analysisExecutor,
                            TelemetryProperties props) {
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.webSocket = webSocket;
        this.metrics = metrics;
        this.analysisExecutor = analysisExecutor;
        this.windowMs = props.window().toMillis();
        this.cooldownMs = props.cooldown().toMillis();
        this.threshold = props.errorThreshold();
        this.maxBuffered = props.maxBufferedEvents();
    }

    @KafkaListener(topics = "${telemetry.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(String payload) {
        LogEvent event;
        try {
            event = objectMapper.readValue(payload, LogEvent.class);
        } catch (JsonProcessingException e) {
            metrics.malformed.increment();
            log.debug("Dropping malformed log record: {}", e.getOriginalMessage());
            return;   // never rethrow: a poison record must not stall the partition
        }

        metrics.received.increment();
        webSocket.broadcastLog(event);

        if (!event.isError()) {
            return;
        }
        metrics.errors.increment();

        List<LogEvent> burst = windows
                .computeIfAbsent(event.service(), k -> new ErrorWindow())
                .record(event, System.currentTimeMillis(), windowMs, threshold, cooldownMs, maxBuffered);
        if (burst.isEmpty()) {
            return;
        }
        metrics.alerts.increment();
        try {
            analysisExecutor.submit(() -> analyze(event.service(), burst));
        } catch (RejectedExecutionException e) {
            log.warn("Analysis executor is shut down; dropping burst for {}", event.service());
        }
    }

    private void analyze(String service, List<LogEvent> burst) {
        try {
            webSocket.broadcastAlert(engine.analyze(service, burst));
        } catch (RuntimeException e) {
            log.error("Incident analysis failed for {}", service, e);
        }
    }
}
