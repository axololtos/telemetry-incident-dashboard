CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS vector;

-- Semantic cache: one row per distinct diagnosed incident pattern.
-- 1536 dims = OpenAI text-embedding-3-small. Change both here and in the embedding model together.
CREATE TABLE IF NOT EXISTS diagnosis_cache (
    id          BIGSERIAL PRIMARY KEY,
    fingerprint TEXT        NOT NULL UNIQUE,
    embedding   vector(1536),
    context     TEXT        NOT NULL,
    diagnosis   TEXT        NOT NULL,
    hits        INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS diagnosis_cache_embedding_idx
    ON diagnosis_cache USING hnsw (embedding vector_cosine_ops);

-- Incident history as a time-series hypertable.
CREATE TABLE IF NOT EXISTS incidents (
    ts           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    service      TEXT             NOT NULL,
    error_count  INTEGER          NOT NULL,
    source       TEXT             NOT NULL,   -- CACHE_EXACT | CACHE_SEMANTIC | LLM | FALLBACK
    similarity   DOUBLE PRECISION NOT NULL,
    tokens_used  INTEGER          NOT NULL,
    diagnosis    TEXT             NOT NULL
);

SELECT create_hypertable('incidents', 'ts', if_not_exists => TRUE);
