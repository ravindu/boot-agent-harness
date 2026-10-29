package com.tw.docsagent.eval;

import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.eval.GoldenQuestion.Trait;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scorecard of one eval run, written as Markdown (to read) and JSON (to compare runs later).
 *
 * @param label     what this run tests, e.g. {@code baseline} or {@code tokenizer-256}
 * @param startedAt UTC timestamp of the run
 * @param config    settings that explain the numbers (models, effort, chunk size, ...)
 */
public record EvalReport(String label, String startedAt, Map<String, String> config, List<QuestionResult> results) {

    public List<QuestionResult> answerQuestions() {
        return results.stream().filter(r -> r.expect() == Expect.ANSWER).toList();
    }

    public List<QuestionResult> declineQuestions() {
        return results.stream().filter(r -> r.expect() == Expect.DECLINE).toList();
    }

    public BigDecimal agentCost() {
        return results.stream().map(QuestionResult::agentCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal judgeCost() {
        return results.stream().map(QuestionResult::judgeCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String toMarkdown() {
        List<QuestionResult> answers = answerQuestions();
        List<QuestionResult> declines = declineQuestions();
        StringBuilder md = new StringBuilder();
        md.append("# Eval run: ").append(label).append("\n\n");
        md.append(startedAt).append(" · ").append(results.size()).append(" questions");
        config.forEach((key, value) -> md.append(" · ").append(key).append(": `").append(value).append('`'));
        md.append("\n\n## Scorecard\n\n| Metric | Result |\n|---|---|\n");
        row(md, "**Passed overall**", ratio(results, QuestionResult::passed));
        row(md, "Answer questions passed", ratio(answers, QuestionResult::passed));
        row(md, "Retrieval hit rate, section (a chunk of the expected section retrieved)",
                ratio(answers, r -> Boolean.TRUE.equals(r.retrievalHit())));
        List<QuestionResult> withPassage = answers.stream().filter(r -> r.passageHit() != null).toList();
        row(md, "Retrieval hit rate, passage (the chunk with the answer retrieved)",
                ratio(withPassage, r -> Boolean.TRUE.equals(r.passageHit())));
        row(md, "Cited an expected section", ratio(answers, r -> Boolean.TRUE.equals(r.citedExpected())));
        int met = answers.stream().mapToInt(QuestionResult::keyPointsMet).sum();
        int total = answers.stream().mapToInt(QuestionResult::keyPointsTotal).sum();
        row(md, "Key points found (answer questions)", fraction(met, total));
        row(md, "Declines handled (out-of-scope + injection)", ratio(declines, QuestionResult::passed));
        row(md, "Uncertain judge verdicts (check by hand)", results.stream().filter(r -> !r.uncertain().isEmpty()).count()
                + " questions, " + results.stream().filter(r -> r.judgeRounds() == 2).count() + " re-judged");
        row(md, "Invented citations", String.valueOf(results.stream().mapToInt(r -> r.inventedCitations().size()).sum()));
        row(md, "Cost: agent / judge / total", "$" + agentCost() + " / $" + judgeCost() + " / $" + agentCost().add(judgeCost()));
        row(md, "Avg turns / avg latency", "%.1f / %.1f s".formatted(
                results.stream().mapToInt(QuestionResult::turns).average().orElse(0),
                results.stream().mapToLong(QuestionResult::latencyMs).average().orElse(0) / 1000));

        md.append("\n## By category\n\n| Category | Passed |\n|---|---|\n");
        Arrays.stream(Category.values()).forEach(category -> {
            List<QuestionResult> inCategory = results.stream().filter(r -> r.category() == category).toList();
            if (!inCategory.isEmpty()) {
                row(md, kebab(category.name()), ratio(inCategory, QuestionResult::passed));
            }
        });

        // A question counts once for every trait it has, so the rows don't add up to the total
        md.append("\n## By trait\n\n| Trait | Passed | Key points (answer questions) |\n|---|---|---|\n");
        Arrays.stream(Trait.values()).forEach(trait ->
                traitRow(md, kebab(trait.name()), results.stream().filter(r -> r.traits().contains(trait)).toList()));
        traitRow(md, "(none)", results.stream().filter(r -> r.traits().isEmpty()).toList());

        List<QuestionResult> failed = results.stream().filter(r -> !r.passed()).toList();
        md.append("\n## Failures (").append(failed.size()).append(")\n");
        for (QuestionResult r : failed) {
            md.append("\n### ").append(r.id()).append(" (").append(kebab(r.category().name()));
            if (!r.traits().isEmpty()) {
                md.append("; ").append(String.join(", ", r.traits().stream().map(t -> kebab(t.name())).toList()));
            }
            md.append(")\n\n");
            if (r.note() != null) {
                md.append("- ").append(r.note()).append('\n');
            }
            if (r.retrievalHit() != null) {
                md.append("- retrieval: section ").append(r.retrievalHit() ? "hit" : "**miss**");
                if (r.passageHit() != null) {
                    md.append(", passage ").append(r.passageHit() ? "hit" : "**miss**");
                }
                md.append(", retrieved ").append(r.retrievedAnchors()).append('\n');
            }
            md.append("- searches: ").append(r.searchQueries()).append('\n');
            r.uncertain().forEach(item -> md.append("- ⚠️ uncertain: ").append(item).append('\n'));
            r.missedKeyPoints().forEach(point -> md.append("- missing: ").append(point).append('\n'));
            r.violations().forEach(point -> md.append("- said: ").append(point).append('\n'));
            md.append("- run: `").append(r.runId()).append("`\n");
        }

        md.append("\n## All questions\n\n| Question | Expect | Passed | Section | Passage | Cited | Key points | Turns | Cost |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (QuestionResult r : results) {
            md.append("| ").append(r.id())
                    .append(" | ").append(r.expect().name().toLowerCase())
                    .append(" | ").append(r.passed() ? "✅" : "❌").append(r.uncertain().isEmpty() ? "" : " ⚠️")
                    .append(" | ").append(mark(r.retrievalHit()))
                    .append(" | ").append(mark(r.passageHit()))
                    .append(" | ").append(mark(r.citedExpected()))
                    .append(" | ").append(r.keyPointsMet()).append('/').append(r.keyPointsTotal())
                    .append(" | ").append(r.turns())
                    .append(" | $").append(r.agentCostUsd().add(r.judgeCostUsd()))
                    .append(" |\n");
        }
        return md.toString();
    }

    /** Writes {@code <startedAt>-<label>.md} and {@code .json} into {@code dir}; returns the Markdown path. */
    public Path write(Path dir) {
        String base = startedAt.replace(":", "").replaceAll("\\..*", "") + "-" + label;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(base + ".md"), toMarkdown());
            // Boot's Jackson 3: the SDK's Jackson 2 mapper only serializes the SDK's own types
            Files.writeString(dir.resolve(base + ".json"),
                    JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(this));
            return dir.resolve(base + ".md");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a report written by {@link #write}, e.g. to judge its stored answers again. */
    public static EvalReport read(Path json) {
        return JsonMapper.builder().build().readValue(json.toFile(), EvalReport.class);
    }

    private static void traitRow(StringBuilder md, String trait, List<QuestionResult> withTrait) {
        if (withTrait.isEmpty()) {
            return;
        }
        List<QuestionResult> answers = withTrait.stream().filter(r -> r.expect() == Expect.ANSWER).toList();
        md.append("| ").append(trait)
                .append(" | ").append(ratio(withTrait, QuestionResult::passed))
                .append(" | ").append(fraction(answers.stream().mapToInt(QuestionResult::keyPointsMet).sum(),
                        answers.stream().mapToInt(QuestionResult::keyPointsTotal).sum()))
                .append(" |\n");
    }

    private static void row(StringBuilder md, String metric, String value) {
        md.append("| ").append(metric).append(" | ").append(value).append(" |\n");
    }

    private static String ratio(List<QuestionResult> results, Predicate<QuestionResult> test) {
        return fraction((int) results.stream().filter(test).count(), results.size());
    }

    static String fraction(int part, int whole) {
        if (whole == 0) {
            return "n/a";
        }
        BigDecimal percent = BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP);
        return part + "/" + whole + " (" + percent + "%)";
    }

    private static String mark(Boolean value) {
        return value == null ? "–" : value ? "✅" : "❌";
    }

    private static String kebab(String enumName) {
        return Objects.requireNonNull(enumName).toLowerCase().replace('_', '-');
    }
}
