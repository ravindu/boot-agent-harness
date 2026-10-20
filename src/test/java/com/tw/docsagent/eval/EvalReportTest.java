package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalReportTest {

    private final EvalReport report = new EvalReport("baseline", "2026-10-21T08:15:00Z",
            Map.of("agent", "claude-sonnet-5-5 (effort medium)"),
            List.of(
                    result("pass-hit", Expect.ANSWER, true, true, 3, 3, null),
                    result("fail-miss", Expect.ANSWER, false, false, 1, 3, null),
                    result("kafka", Expect.DECLINE, true, null, 2, 2, null),
                    result("injection", Expect.DECLINE, false, null, 0, 2, "judge failed: boom")));

    @Test
    void scorecardSeparatesAnswerAndDeclineQuestions() {
        String md = report.toMarkdown();

        assertThat(md)
                .contains("# Eval run: baseline")
                .contains("| **Passed overall** | 2/4 (50%) |")
                .contains("| Answer questions passed | 1/2 (50%) |")
                .contains("| Retrieval hit rate (expected section retrieved) | 1/2 (50%) |")
                .contains("| Key points found (answer questions) | 4/6 (67%) |")
                .contains("| Declines handled (out-of-scope + injection) | 1/2 (50%) |")
                .contains("| Cost: agent / judge / total | $0.080 / $0.0040 / $0.0840 |");
    }

    @Test
    void failuresListTheReasonAndTheRunId() {
        String md = report.toMarkdown();

        assertThat(md)
                .contains("## Failures (2)")
                .contains("### fail-miss (how-to)")
                .contains("- retrieval: **miss**, retrieved [other.anchor]")
                .contains("- missing: key point (judge: not stated)")
                .contains("### injection (how-to)\n\n- judge failed: boom");
    }

    @Test
    void writesMarkdownAndJsonNamedAfterTheRun(@TempDir Path dir) throws IOException {
        Path md = report.write(dir);

        assertThat(md.getFileName().toString()).isEqualTo("2026-10-21T081500Z-baseline.md");
        assertThat(Files.readString(dir.resolve("2026-10-21T081500Z-baseline.json")))
                .contains("\"label\" : \"baseline\"")
                .contains("\"id\" : \"fail-miss\"");
    }

    @Test
    void fractionHandlesAnEmptyGroup() {
        assertThat(EvalReport.fraction(0, 0)).isEqualTo("n/a");
    }

    private static QuestionResult result(String id, Expect expect, boolean passed, Boolean retrievalHit,
                                         int met, int total, String note) {
        return new QuestionResult(id, Category.HOW_TO, expect, passed, RunStatus.COMPLETED, retrievalHit,
                retrievalHit, met, total, passed ? List.of() : List.of("key point (judge: not stated)"), List.of(),
                List.of(), retrievalHit == null || retrievalHit ? List.of("expected.anchor") : List.of("other.anchor"),
                List.of(), 2, 1500, new BigDecimal("0.020"), new BigDecimal("0.0010"), UUID.randomUUID(), note, "answer");
    }
}
