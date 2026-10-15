package com.archnode.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Shrinks a burst of error events into a compact LLM context (target: under ~500 tokens):
 * strips boilerplate (ANSI codes, timestamps, UUIDs, framework-internal frames), truncates stack
 * traces to the top 12 lines and collapses repeated errors into a single "xN" entry.
 */
@Component
public class LogPayloadReducer {

    public static final int MAX_STACK_LINES = 12;
    public static final int MAX_CONTEXT_CHARS = 2000;   // ~4 chars/token => ~500 tokens
    static final int MAX_LINE_CHARS = 200;
    static final int MAX_MESSAGE_CHARS = 300;
    static final int MAX_DISTINCT_ERRORS = 5;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern ISO_TS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NOISE_FRAME = Pattern.compile(
            "^\\s*at (?:java\\.base/|jdk\\.internal\\.|sun\\.reflect\\.|org\\.springframework\\.cglib\\.|"
                    + "org\\.springframework\\.aop\\.|org\\.apache\\.(?:catalina|tomcat|coyote)\\.|org\\.junit\\.).*");
    private static final Pattern OMITTED = Pattern.compile("^\\s*\\.\\.\\. \\d+ (?:more|common frames omitted)\\s*$");

    private static final class Group {
        final LogEvent sample;
        int count;

        Group(LogEvent sample) {
            this.sample = sample;
        }
    }

    /** Builds the compact context for a burst of events (normally all from one service). */
    public String reduce(List<LogEvent> events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        for (LogEvent e : events) {
            groups.computeIfAbsent(fingerprint(e), k -> new Group(e)).count++;
        }
        List<Group> top = groups.values().stream()
                .sorted(Comparator.comparingInt((Group g) -> g.count).reversed())
                .limit(MAX_DISTINCT_ERRORS)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("service=").append(events.get(0).service())
                .append(" errors=").append(events.size())
                .append(" distinct=").append(groups.size()).append('\n');

        int index = 1;
        for (Group g : top) {
            StringBuilder entry = new StringBuilder();
            entry.append('[').append(index++).append("] x").append(g.count).append(' ')
                    .append(g.sample.level()).append(' ')
                    .append(cap(scrub(g.sample.message()), MAX_MESSAGE_CHARS)).append('\n');
            String stack = truncateStackTrace(g.sample.stackTrace());
            if (!stack.isEmpty()) {
                entry.append(stack).append('\n');
            }
            int room = MAX_CONTEXT_CHARS - sb.length();
            if (entry.length() > room) {
                sb.append(cap(entry.toString(), room));
                break;
            }
            sb.append(entry);
        }
        return sb.toString().strip();
    }

    /** Drops boilerplate frames and keeps only the top {@value #MAX_STACK_LINES} lines of a stack trace. */
    public String truncateStackTrace(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        int total = 0;
        for (String raw : stackTrace.split("\\R")) {
            if (raw.isBlank() || NOISE_FRAME.matcher(raw).matches() || OMITTED.matcher(raw).matches()) {
                continue;
            }
            total++;
            if (kept.size() < MAX_STACK_LINES) {
                kept.add(cap(ANSI.matcher(raw).replaceAll("").stripTrailing(), MAX_LINE_CHARS));
            }
        }
        if (total > kept.size()) {
            kept.add("    ... " + (total - kept.size()) + " more lines truncated");
        }
        return String.join("\n", kept);
    }

    /** Stable hash of an event with volatile parts (ids, numbers, timestamps) normalised away. */
    public String fingerprint(LogEvent e) {
        String topFrames = truncateStackTrace(e.stackTrace()).lines().limit(3).collect(Collectors.joining("\n"));
        return fingerprint(e.service() + "|" + e.level() + "|" + e.message() + "|" + topFrames);
    }

    public String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", ex);
        }
    }

    /** Rough token estimate (~4 characters per token) for metrics only. */
    public int estimateTokens(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }

    private static String scrub(String s) {
        String out = ANSI.matcher(s).replaceAll("");
        out = ISO_TS.matcher(out).replaceAll("");
        out = UUID.matcher(out).replaceAll("<uuid>");
        out = HEX.matcher(out).replaceAll("<hex>");
        return WHITESPACE.matcher(out).replaceAll(" ").strip();
    }

    private static String normalize(String s) {
        return NUMBER.matcher(scrub(s)).replaceAll("<n>");
    }

    private static String cap(String s, int max) {
        if (max <= 0) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
