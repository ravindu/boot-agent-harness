package com.tw.docsagent.retrieval.ingest;

import java.util.List;

/**
 * What one ingestion run stored.
 *
 * @param missingExamples include-code targets that could not be fetched; their sections are stored without the code
 */
public record IngestionResult(
        String bootVersion,
        int pages,
        int sections,
        int chunks,
        int resolvedExamples,
        List<String> missingExamples,
        long durationMs) {
}
