package com.tw.docsagent.retrieval.ingest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxWordsPerChunk upper bound per chunk; ~200 words stays under the 256-token window of all-MiniLM-L6-v2
 */
@Validated
@ConfigurationProperties("docsagent.ingestion")
public record IngestionProperties(@Positive @Max(1000) int maxWordsPerChunk) {
}
