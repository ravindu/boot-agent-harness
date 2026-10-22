package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.agent.RunStatus;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.eval.GoldenQuestion.Trait;
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
                    result("pass-hit", Expect.ANSWER, List.of(), true, true, true, 3, 3, List.of(), null),
                    // the baseline's typical failure: right section, wrong chunk
                    result("fail-miss", Expect.ANSWER, List.of(Trait.MULTI_HOP, Trait.PARAPHRASED), false, true,
                            false, 1, 3, List.of("key point: key point"), null),
                    result("kafka", Expect.DECLINE, List.of(Trait.DISTRACTOR), true, null, null, 2, 2, List.of(), null),
                    result("injection", Expect.DECLINE, List.of(Trait.PARAPHRASED), false, null, null, 0, 2, List.of(),
                            "judge failed: boom")));

    @Test
    void scorecardSeparatesAnswerAndDeclineQuestions() {
        String md = report.toMarkdown();

        assertThat(md)
                .contains("# Eval run: baseline")
                .contains("| **Passed overall** | 2/4 (50%) |")
                .contains("| Answer questions passed | 1/2 (50%) |")
                .contains("| Retrieval hit rate, section (a chunk of the expected section retrieved) | 2/2 (100%) |")
                .contains("| Retrieval hit rate, passage (the chunk with the answer retrieved) | 1/2 (50%) |")
                .contains("| Uncertain judge verdicts (check by hand) | 1 questions, 1 re-judged |")
                .contains("| Key points found (answer questions) | 4/6 (67%) |")
                .contains("| Declines handled (out-of-scope + injection) | 1/2 (50%) |")
                .contains("| Cost: agent / judge / total | $0.080 / $0.0040 / $0.0840 |");
    }

    @Test
    void failuresListTheReasonAndTheRunId() {
        String md = report.toMarkdown();

        assertThat(md)
                .contains("## Failures (2)")
                .contains("### fail-miss (how-to; multi-hop, paraphrased)")
                .contains("- retrieval: section hit, passage **miss**, retrieved [expected.anchor]")
                .contains("- searches: [service connection]")
                .contains("- ⚠️ uncertain: key point: key point")
                .contains("- missing: key point (judge: not stated)")
                .contains("### injection (how-to; paraphrased)\n\n- judge failed: boom");
    }

    @Test
    void byTraitCountsAQuestionUnderEachOfItsTraitsAndKeyPointsOnlyForAnswerQuestions() {
        String md = report.toMarkdown();

        assertThat(md)
                .contains("| Trait | Passed | Key points (answer questions) |")
                .contains("| paraphrased | 0/2 (0%) | 1/3 (33%) |")
                .contains("| multi-hop | 0/1 (0%) | 1/3 (33%) |")
                .contains("| distractor | 1/1 (100%) | n/a |")
                .contains("| (none) | 1/1 (100%) | 3/3 (100%) |")
                .doesNotContain("| boot3-habit |");
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
    void aWrittenReportReadsBackUnchangedSoItCanBeJudgedAgain(@TempDir Path dir) {
        report.write(dir);

        EvalReport read = EvalReport.read(dir.resolve("2026-10-21T081500Z-baseline.json"));

        assertThat(read).usingRecursiveComparison().isEqualTo(report);
    }

    @Test
    void fractionHandlesAnEmptyGroup() {
        assertThat(EvalReport.fraction(0, 0)).isEqualTo("n/a");
    }

    private static QuestionResult result(String id, Expect expect, List<Trait> traits, boolean passed,
                                         Boolean sectionHit, Boolean passageHit, int met, int total,
                                         List<String> uncertain, String note) {
        return new QuestionResult(id, Category.HOW_TO, expect, traits, passed, RunStatus.COMPLETED, sectionHit, passageHit,
                sectionHit, met, total, passed ? List.of() : List.of("key point (judge: not stated)"), List.of(),
                uncertain, uncertain.isEmpty() ? 1 : 2, List.of(), List.of("expected.anchor"), List.of(),
                List.of("service connection"), List.of("chunk-1"), 2, 1500, new BigDecimal("0.020"),
                new BigDecimal("0.0010"), UUID.randomUUID(), note, "answer");
    }
}
