package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrievalReportTest {

    private final RetrievalReport report = new RetrievalReport("retrieval-baseline", "2026-10-21T09:00:00Z",
            Map.of("maxWordsPerChunk", "200"), "2026-10-20T065749Z-baseline-v2.json",
            List.of(
                    new RetrievalReport.Result("top", List.of("a"), 1, List.of("q1"), 1, List.of("x#0")),
                    new RetrievalReport.Result("sixth", List.of("b"), 6, List.of("q2"), 2,
                            List.of("testing.testcontainers.service-connections#1", "y#0")),
                    new RetrievalReport.Result("lost", List.of("c"), 0, List.of(), null, List.of("z#3"))));

    @Test
    void scorecardShowsHitRatesAndMrrPerQuerySet() {
        String md = report.toMarkdown();

        assertThat(md)
                .contains("# Retrieval eval: retrieval-baseline")
                // question text: ranks 1, 6, miss -> hit@5 1/3, hit@8 2/3, MRR (1 + 1/6 + 0) / 3 = 0.39
                .contains("| Golden question text | 1/3 (33%) | 2/3 (67%) | 0.39 |")
                // replayed queries only exist for two questions: ranks 1, 2 -> MRR 0.75
                .contains("| Claude's queries, replayed from `2026-10-20T065749Z-baseline-v2.json` | 2/2 (100%) | 2/2 (100%) | 0.75 |");
    }

    @Test
    void missesShowWhatCameFirstInstead() {
        String md = report.toMarkdown();

        assertThat(md)
                .contains("## Not in the top 5 for the question text (2)")
                .contains("### sixth: rank 6\n\n- expected: [b]\n- top 5 instead: [testing.testcontainers.service-connections#1, y#0]")
                .contains("- with Claude's queries [q2]: rank 2")
                .contains("### lost: rank >20")
                .contains("| lost | >20 | – |");
    }

    @Test
    void writesMarkdownAndJson(@TempDir Path dir) throws IOException {
        report.write(dir);

        assertThat(Files.readString(dir.resolve("2026-10-21T090000Z-retrieval-baseline.json")))
                .contains("\"questionRank\" : 6");
    }
}
