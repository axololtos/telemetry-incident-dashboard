package com.archnode.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogPayloadReducerTest {

    private final LogPayloadReducer reducer = new LogPayloadReducer();

    private static String stack(int frames) {
        StringBuilder sb = new StringBuilder("java.sql.SQLException: pool exhausted\n");
        for (int i = 0; i < frames; i++) {
            sb.append("\tat com.shop.Repo.method").append(i).append("(Repo.java:").append(i).append(")\n");
            sb.append("\tat java.base/jdk.internal.reflect.Noise.call(Noise.java:1)\n");
        }
        return sb.toString();
    }

    @Test
    void truncatesStackToTwelveLinesAndDropsNoise() {
        String out = reducer.truncateStackTrace(stack(40));
        List<String> lines = out.lines().toList();
        assertEquals(LogPayloadReducer.MAX_STACK_LINES + 1, lines.size());   // 12 + "more lines" marker
        assertFalse(out.contains("java.base/"));
        assertTrue(lines.get(lines.size() - 1).contains("more lines truncated"));
    }

    @Test
    void collapsesRepeatedErrorsAndStaysUnderBudget() {
        List<LogEvent> events = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            events.add(new LogEvent(null, "orders", "ERROR",
                    "Timeout after " + (3000 + i) + "ms for order " + java.util.UUID.randomUUID(), stack(40), "t" + i, "h1"));
        }
        String out = reducer.reduce(events);
        assertTrue(out.contains("x200"), out);
        assertTrue(out.contains("distinct=1"), out);
        assertTrue(out.length() <= LogPayloadReducer.MAX_CONTEXT_CHARS);
    }

    @Test
    void fingerprintIgnoresVolatileParts() {
        LogEvent a = new LogEvent(null, "orders", "ERROR", "Timeout after 3001ms for 7f9c1b2a-1111-2222-3333-444455556666", "", "", "");
        LogEvent b = new LogEvent(null, "orders", "ERROR", "Timeout after 9999ms for 00000000-1111-2222-3333-444455556666", "", "", "");
        assertEquals(reducer.fingerprint(a), reducer.fingerprint(b));
    }
}
