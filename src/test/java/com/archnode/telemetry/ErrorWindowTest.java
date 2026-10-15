package com.archnode.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorWindowTest {

    private static final long WINDOW = 60_000;
    private static final long COOLDOWN = 120_000;
    private static final LogEvent ERR = new LogEvent(null, "orders", "ERROR", "boom", "", "", "");

    private static List<LogEvent> record(KafkaLogConsumer.ErrorWindow w, long now) {
        return w.record(ERR, now, WINDOW, 3, COOLDOWN, 100);
    }

    @Test
    void firesAtThresholdThenCoolsDown() {
        var w = new KafkaLogConsumer.ErrorWindow();
        assertTrue(record(w, 0).isEmpty());
        assertTrue(record(w, 1).isEmpty());
        assertEquals(3, record(w, 2).size());      // threshold reached
        assertTrue(record(w, 3).isEmpty());        // inside cooldown
    }

    @Test
    void oldErrorsFallOutOfTheWindow() {
        var w = new KafkaLogConsumer.ErrorWindow();
        record(w, 0);
        record(w, 1);
        assertTrue(record(w, 100_000).isEmpty());  // first two expired, only one left
    }
}
