package com.archnode.telemetry;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Incident diagnosis pipeline, cheapest step first:
 * exact fingerprint match (0 tokens) -> pgvector semantic match >= threshold (0 tokens, 1 embedding call)
 * -> LLM call on a miss, whose answer is written back to the cache.
 */
@Service
public class LogAnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(LogAnalysisEngine.class);

    static final int EMBEDDING_DIMENSIONS = 1536;          // must match docker/init.sql
    private static final int PROMPT_OVERHEAD_TOKENS = 80;  // system prompt, for the spend estimate
    private static final int TYPICAL_ANSWER_TOKENS = 250;  // for the "tokens saved" estimate

    public interface IncidentAssistant {
        @SystemMessage("""
                You are a senior SRE assisting with live incident triage.
                You receive condensed error telemetry: repeated errors are collapsed to "xN" and stack traces are truncated.
                Reply in under 150 words, plain text, using exactly these headings:
                Root cause: the most likely cause, and your confidence (low/medium/high).
                Impact: what is probably affected.
                Fix: at most three concrete steps, most urgent first.
                Do not invent details that are not in the telemetry.""")
        @UserMessage("Telemetry:\n{{context}}")
        String diagnose(@V("context") String context);
    }

    public record Diagnosis(String text, String source, double similarity, int tokensUsed) {
    }

    public record Incident(Instant at, String service, int errorCount, String context, Diagnosis diagnosis) {
    }

    private record Match(long id, String diagnosis, double similarity) {
    }

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;
    private final IncidentAssistant assistant;
    private final LogPayloadReducer reducer;
    private final TelemetryMetrics metrics;
    private final double similarityThreshold;
    private final ConcurrentMap<String, CompletableFuture<Diagnosis>> inFlight = new ConcurrentHashMap<>();

    public LogAnalysisEngine(JdbcTemplate jdbc, EmbeddingModel embeddingModel, ChatLanguageModel chatModel,
                             LogPayloadReducer reducer, TelemetryMetrics metrics, TelemetryProperties props) {
        this.jdbc = jdbc;
        this.embeddingModel = embeddingModel;
        this.assistant = AiServices.create(IncidentAssistant.class, chatModel);
        this.reducer = reducer;
        this.metrics = metrics;
        this.similarityThreshold = props.similarityThreshold();
    }

    /** Diagnoses a burst of error events from one service and records the incident. Never throws. */
    public Incident analyze(String service, List<LogEvent> events) {
        String context = reducer.reduce(events);
        Diagnosis diagnosis = context.isEmpty()
                ? new Diagnosis("No events to analyse.", "FALLBACK", 0, 0)
                : diagnoseOnce(context);
        Incident incident = new Incident(Instant.now(), service, events.size(), context, diagnosis);
        persistIncident(incident);
        return incident;
    }

    /** Concurrent identical bursts share one lookup / LLM call (single-flight). */
    private Diagnosis diagnoseOnce(String context) {
        String fingerprint = reducer.fingerprint(context);
        var mine = new CompletableFuture<Diagnosis>();
        var running = inFlight.putIfAbsent(fingerprint, mine);
        if (running != null) {
            return running.join();
        }
        try {
            Diagnosis result = resolve(context, fingerprint);
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(fingerprint);
        }
    }

    private Diagnosis resolve(String context, String fingerprint) {
        // 1. identical error pattern already diagnosed
        try {
            List<String> exact = jdbc.queryForList(
                    "UPDATE diagnosis_cache SET hits = hits + 1 WHERE fingerprint = ? RETURNING diagnosis",
                    String.class, fingerprint);
            if (!exact.isEmpty()) {
                return cacheHit("CACHE_EXACT", exact.get(0), 1.0, context);
            }
        } catch (DataAccessException e) {
            log.warn("Exact cache lookup failed, continuing: {}", e.getMessage());
        }

        // 2. semantically similar error pattern
        float[] embedding = embed(context);
        if (embedding != null) {
            try {
                Match match = nearest(embedding);
                if (match != null && match.similarity() >= similarityThreshold) {
                    jdbc.update("UPDATE diagnosis_cache SET hits = hits + 1 WHERE id = ?", match.id());
                    return cacheHit("CACHE_SEMANTIC", match.diagnosis(), match.similarity(), context);
                }
            } catch (DataAccessException e) {
                log.warn("Vector cache lookup failed, continuing: {}", e.getMessage());
            }
        }

        // 3. cache miss: ask the LLM and remember the answer
        metrics.llmCalls.increment();
        String answer;
        try {
            answer = assistant.diagnose(context);
        } catch (RuntimeException e) {
            metrics.llmFailures.increment();
            log.error("LLM diagnosis failed: {}", e.toString());
            return new Diagnosis("Automated diagnosis unavailable (" + e.getClass().getSimpleName()
                    + "). See the condensed telemetry attached to this incident.", "FALLBACK", 0, 0);
        }
        int tokens = reducer.estimateTokens(context) + reducer.estimateTokens(answer) + PROMPT_OVERHEAD_TOKENS;
        metrics.tokensSpent.add(tokens);
        store(fingerprint, embedding, context, answer);
        return new Diagnosis(answer, "LLM", 0, tokens);
    }

    private Diagnosis cacheHit(String source, String text, double similarity, String context) {
        metrics.cacheHits.increment();
        metrics.tokensSaved.add(reducer.estimateTokens(context) + PROMPT_OVERHEAD_TOKENS + TYPICAL_ANSWER_TOKENS);
        return new Diagnosis(text, source, similarity, 0);
    }

    private float[] embed(String context) {
        try {
            float[] vector = embeddingModel.embed(context).content().vector();
            if (vector.length != EMBEDDING_DIMENSIONS) {
                log.warn("Embedding has {} dimensions, schema expects {}; skipping vector cache",
                        vector.length, EMBEDDING_DIMENSIONS);
                return null;
            }
            return vector;
        } catch (RuntimeException e) {
            log.warn("Embedding failed, skipping vector cache: {}", e.toString());
            return null;
        }
    }

    private Match nearest(float[] embedding) {
        String literal = toVectorLiteral(embedding);
        List<Match> rows = jdbc.query("""
                        SELECT id, diagnosis, 1 - (embedding <=> ?::vector) AS similarity
                        FROM diagnosis_cache
                        WHERE embedding IS NOT NULL
                        ORDER BY embedding <=> ?::vector
                        LIMIT 1""",
                (rs, i) -> new Match(rs.getLong("id"), rs.getString("diagnosis"), rs.getDouble("similarity")),
                literal, literal);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void store(String fingerprint, float[] embedding, String context, String diagnosis) {
        try {
            jdbc.update("""
                            INSERT INTO diagnosis_cache (fingerprint, embedding, context, diagnosis)
                            VALUES (?, ?::vector, ?, ?)
                            ON CONFLICT (fingerprint) DO NOTHING""",
                    fingerprint, embedding == null ? null : toVectorLiteral(embedding), context, diagnosis);
        } catch (DataAccessException e) {
            log.warn("Could not cache diagnosis: {}", e.getMessage());
        }
    }

    private void persistIncident(Incident incident) {
        Diagnosis d = incident.diagnosis();
        try {
            jdbc.update("""
                            INSERT INTO incidents (ts, service, error_count, source, similarity, tokens_used, diagnosis)
                            VALUES (?::timestamptz, ?, ?, ?, ?, ?, ?)""",
                    incident.at().toString(), incident.service(), incident.errorCount(),
                    d.source(), d.similarity(), d.tokensUsed(), d.text());
        } catch (DataAccessException e) {
            log.warn("Could not persist incident: {}", e.getMessage());
        }
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
