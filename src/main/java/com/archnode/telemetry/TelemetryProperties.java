package com.archnode.telemetry;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("telemetry")
public record TelemetryProperties(
        @DefaultValue("app-logs") String topic,
        @DefaultValue("3") int partitions,
        @DefaultValue("3") int concurrency,
        @DefaultValue("60s") Duration window,
        @DefaultValue("10") int errorThreshold,
        @DefaultValue("120s") Duration cooldown,
        @DefaultValue("200") int maxBufferedEvents,
        @DefaultValue("0.90") double similarityThreshold,
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins) {
}
