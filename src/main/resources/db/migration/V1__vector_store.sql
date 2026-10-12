-- Vector store for doc chunks. Mirrors the table Spring AI's PgVectorStore expects
-- (spring.ai.vectorstore.pgvector.initialize-schema=false, schema-validation=true),
-- so Flyway owns the schema.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 384 = output size of the local all-MiniLM-L6-v2 ONNX embedding model.
-- Changing the embedding model means a new column size and re-ingesting everything.
CREATE TABLE vector_store
(
    id        uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(384)
);

-- HNSW: approximate nearest-neighbour index; cosine matches distance-type COSINE_DISTANCE.
CREATE INDEX spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops);
