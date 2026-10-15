package com.archnode.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Set;

/** One log line as shipped to Kafka (JSON). Unknown fields are ignored; missing ones get safe defaults. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogEvent(
        Instant timestamp,
        String service,
        String level,
        String message,
        String stackTrace,
        String traceId,
        String host) {

    private static final Set<String> ERROR_LEVELS = Set.of("ERROR", "FATAL", "SEVERE", "CRITICAL");

    public LogEvent {
        timestamp = timestamp != null ? timestamp : Instant.now();
        service = isBlank(service) ? "unknown" : service.strip();
        level = isBlank(level) ? "INFO" : level.strip().toUpperCase();
        message = message != null ? message : "";
        stackTrace = stackTrace != null ? stackTrace : "";
        traceId = traceId != null ? traceId : "";
        host = host != null ? host : "";
    }

    public boolean isError() {
        return ERROR_LEVELS.contains(level);
    }

    public LogEvent withStackTrace(String trimmed) {
        return new LogEvent(timestamp, service, level, message, trimmed, traceId, host);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
