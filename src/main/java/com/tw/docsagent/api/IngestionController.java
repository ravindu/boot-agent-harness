package com.tw.docsagent.api;

import com.tw.docsagent.retrieval.ingest.IngestionResult;
import com.tw.docsagent.retrieval.ingest.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Triggers ingestion of the configured docs. Synchronous: ten pages embed locally in about a minute. */
@RestController
@RequestMapping("/api/ingestion/runs")
@RequiredArgsConstructor
class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping
    IngestionResult ingest() {
        return ingestionService.ingestAll();
    }
}
