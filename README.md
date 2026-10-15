# Real-Time Telemetry & Incident Analysis Platform

Java 21 / Spring Boot 3 service that consumes application logs from Kafka, detects error bursts locally,
and only calls an LLM when it has never seen a similar incident before. Diagnoses are cached in
PostgreSQL (pgvector) and results are pushed live to a dashboard over STOMP/WebSockets.

```
Apps -> Kafka (KRaft) -> Spring Boot engine
                           1. per-service sliding-window anomaly detector   (0 tokens)
                           2. LogPayloadReducer: boilerplate strip, stack top-12, "xN" dedupe
                           3. cache lookup: exact fingerprint -> pgvector cosine >= 0.90   (0 tokens)
                           4. miss -> LangChain4j / OpenAI, answer written back to cache
                         -> PostgreSQL + TimescaleDB (incidents hypertable) -> WebSocket topics
```

## Highlights
- **Virtual threads end to end:** each Kafka consumer loop and each LLM-bound analysis task runs on a Java 21 virtual thread.
- **Token cost control:** normal logs never leave the JVM. An error burst is squeezed before it reaches a model, and repeats are served from a semantic cache.
- **Single-flight analysis:** concurrent identical bursts share one cache lookup / LLM call.
- **Resilient by design:** malformed records are dropped (no poison-pill stalls), and embedding, DB or LLM failures degrade to a fallback incident instead of crashing the pipeline.
- **Tested:** unit tests for the reducer (12-line truncation, dedupe, fingerprint stability) and the sliding window (threshold, cooldown, expiry).

## Run it
```bash
docker compose up -d                       # Kafka (KRaft) + TimescaleDB/pgvector, schema auto-created
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
scripts/send-sample-logs.sh                # replays sample-data/*.ndjson into Kafka
```
`sample-data/`: `01` normal traffic (300 lines), `02` DB-pool-timeout burst on `orders`, `03` the same failure
on `inventory` (different service, shows a semantic hit), `04` NPE burst on `payments`, `05` malformed lines,
`06` out-of-memory burst on `search`, `07` upstream 502s on `gateway`, `08` JWT failures on `auth`,
`09` six errors plus warnings that must **not** raise an incident (below threshold).

## Dashboard (`dashboard/`)
A small React + Vite app with two modes:
- **Demo** (default): a browser-side simulation of the same rules (10 errors in a window raises an incident,
  then exact cache, similar cache, or LLM). The diagnoses are hand-written, and the page says so. This is the
  mode to host: `Play the story` shows an LLM call, a below-threshold non-event, a similar-error cache hit and an
  exact cache hit in about a minute.
- **Live backend**: connects to the Spring Boot service over STOMP (`/ws`) and shows real logs, incidents and metrics.
  For a page served from another origin, add that origin to `telemetry.allowed-origins`. An `https` page can only
  reach an `https` backend.

```bash
cd dashboard && npm install && npm run dev      # http://localhost:5173
```
Deploy on Vercel: import the GitHub repo, set **Root Directory** to `dashboard`. Vite is detected automatically
(build `npm run build`, output `dist`). The backend does not run on Vercel, so the hosted link is the demo mode.

## Sample input
One Kafka message (the burst in `02-db-timeout-burst.ndjson` is 15 of these):
```json
{"service":"orders","level":"ERROR","host":"orders-1","traceId":"tr-orders-1",
 "message":"HikariPool-1 - Connection is not available, request timed out after 30001ms",
 "stackTrace":"java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30001ms\n\tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)\n\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)\n\tat com.shop.orders.Repository.save(Repository.java:48)\n\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)\n\tat com.shop.orders.Service.handle(Service.java:71)"}
```

## Sample output
**1. What the LLM would receive** (real output of `LogPayloadReducer` for that 15-error burst):
```
service=orders errors=15 distinct=1
[1] x15 ERROR HikariPool-1 - Connection is not available, request timed out after 30001ms
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30001ms
	at com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
	at com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:197)
	at com.shop.orders.Repository.save(Repository.java:48)
	at com.shop.orders.Service.handle(Service.java:71)
```
Raw payload: 8,415 chars (~2,100 tokens). Reduced: 506 chars (~127 tokens), a **94% reduction**. JDK-internal frames and repeats are gone.

**2. Incident row stored in the `incidents` hypertable** (captured from a live run; the OpenAI account used had no
credit, so this shows the graceful-degradation path):
```
service     | orders
error_count | 10
source      | FALLBACK
tokens_used | 0
diagnosis   | Automated diagnosis unavailable (RateLimitException). See the condensed telemetry attached to this incident.
```
(Alert fires at the 10-error threshold, hence `error_count` 10.)

**3. Alert pushed to `/topic/alerts` on a successful LLM call** (shape is real; the diagnosis text is an
illustrative example, not a captured model response):
```json
{"at":"2026-10-15T07:29:55.535Z","service":"orders","errorCount":10,
 "diagnosis":{"source":"LLM","similarity":0.0,"tokensUsed":310,
   "text":"Root cause: DB connection pool exhausted (high). Impact: order writes time out. Fix: 1) check for leaked connections, 2) raise pool size/timeout, 3) inspect slow queries."}}
```
Replaying `03-db-timeout-similar.ndjson` afterwards is designed to return `"source":"CACHE_SEMANTIC"` with
`"tokensUsed":0` and the same diagnosis text.

**4. Metrics on `/topic/metrics`** every 2 seconds: received, errors, alerts, cacheHits, llmCalls, tokensSpent,
tokensSaved, cacheHitRatio, logsPerSecond.

## Stack
Java 21, Spring Boot 3.3, Spring Kafka, Spring WebSocket (STOMP), Spring Data JPA, LangChain4j 1.0.0-beta3 (OpenAI),
Apache Kafka (KRaft), PostgreSQL + TimescaleDB + pgvector (HNSW index), Docker Compose, JUnit 5.
