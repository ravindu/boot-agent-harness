package com.tw.docsagent.agent;

import com.tw.docsagent.tool.SourceCatalog;
import com.tw.docsagent.tool.SourceCatalog.Source;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps {@code [S3]} markers in an answer back to the chunks Claude was shown.
 *
 * <p>A marker whose label the harness never handed out is an <em>invented citation</em>: the model
 * cited something it did not retrieve. That is a grounding failure we can detect without a judge,
 * which is why the labels exist (M2 turns it into a metric).
 */
public final class CitationChecker {

    /** {@code [S1]}, {@code [S1, S3]}, {@code [S1][S2]}. */
    private static final Pattern BRACKET = Pattern.compile("\\[(S\\d+(?:\\s*,\\s*S\\d+)*)]");
    private static final Pattern LABEL = Pattern.compile("S\\d+");

    public record Report(List<Source> cited, List<String> invented) {
    }

    private CitationChecker() {
    }

    public static Report check(String answer, SourceCatalog catalog) {
        Set<String> labels = new LinkedHashSet<>();
        Matcher bracket = BRACKET.matcher(answer == null ? "" : answer);
        while (bracket.find()) {
            Matcher label = LABEL.matcher(bracket.group(1));
            while (label.find()) {
                labels.add(label.group());
            }
        }
        List<Source> cited = new ArrayList<>();
        List<String> invented = new ArrayList<>();
        for (String label : labels) {
            Optional<Source> source = catalog.resolve(label);
            source.ifPresentOrElse(cited::add, () -> invented.add(label));
        }
        return new Report(cited, invented);
    }
}
