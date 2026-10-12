package com.tw.docsagent.retrieval.ingest;

import com.tw.docsagent.retrieval.source.DocsSource;
import com.tw.docsagent.retrieval.source.DocsSourceProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Ingestion pipeline: read → parse → chunk → embed → store.
 *
 * <p>Embedding happens inside {@link VectorStore#add}: Spring AI calls the local ONNX model for every
 * chunk and writes text, metadata and the 384-dimension vector to {@code vector_store}.
 *
 * <p>Re-runnable: a page's old chunks are deleted (by {@code bootVersion} + {@code page} metadata)
 * before its new chunks are added, and chunk ids are derived from version, page, anchor and position.
 */
@Slf4j
@Service
public class IngestionService {

    private final DocsSource docsSource;
    private final DocsSourceProperties docsProperties;
    private final VectorStore vectorStore;
    private final AsciiDocParser parser = new AsciiDocParser();
    private final Chunker chunker;

    public IngestionService(DocsSource docsSource, DocsSourceProperties docsProperties,
                            IngestionProperties ingestionProperties, VectorStore vectorStore) {
        this.docsSource = docsSource;
        this.docsProperties = docsProperties;
        this.vectorStore = vectorStore;
        this.chunker = new Chunker(ingestionProperties.maxWordsPerChunk());
    }

    public IngestionResult ingestAll() {
        Instant start = Instant.now();
        String version = docsProperties.bootVersion();
        int sectionCount = 0;
        int chunkCount = 0;
        int resolvedExamples = 0;
        List<String> missingExamples = new ArrayList<>();

        for (String page : docsProperties.pages()) {
            AsciiDocParser.ParsedPage parsed = parser.parse(page, docsSource.readPage(page), docsSource::readExample);
            List<Document> documents = new ArrayList<>();
            for (DocSection section : parsed.sections()) {
                List<String> chunks = chunker.chunk(section);
                for (int i = 0; i < chunks.size(); i++) {
                    documents.add(toDocument(version, section, i, chunks.get(i)));
                }
            }

            FilterExpressionBuilder f = new FilterExpressionBuilder();
            vectorStore.delete(f.and(f.eq("bootVersion", version), f.eq("page", page)).build());
            if (!documents.isEmpty()) {
                vectorStore.add(documents);
            }

            log.info("ingested page={} sections={} chunks={} examples={} missingExamples={}",
                    page, parsed.sections().size(), documents.size(),
                    parsed.resolvedExamples(), parsed.missingExamples().size());
            sectionCount += parsed.sections().size();
            chunkCount += documents.size();
            resolvedExamples += parsed.resolvedExamples();
            missingExamples.addAll(parsed.missingExamples());
        }

        long durationMs = Duration.between(start, Instant.now()).toMillis();
        return new IngestionResult(version, docsProperties.pages().size(), sectionCount, chunkCount,
                resolvedExamples, missingExamples, durationMs);
    }

    private Document toDocument(String version, DocSection section, int index, String text) {
        String key = version + "|" + section.page() + "|" + section.anchor() + "|" + index;
        String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> metadata = Map.of(
                "bootVersion", version,
                "page", section.page(),
                "anchor", section.anchor(),
                "title", section.title(),
                "breadcrumb", section.breadcrumbText(),
                "url", docsProperties.sectionUrl(section.page(), section.anchor()),
                "chunkIndex", index);
        return new Document(id, text, metadata);
    }
}
