package com.tw.docsagent.eval;

import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Loads and validates the golden set. Validation is strict on purpose: a typo in the file should
 * fail the build, not silently turn into a question that can never score.
 *
 * <p>Parsed with SnakeYAML (already on the classpath through spring-boot-starter) into plain maps,
 * then mapped by hand, so every rule is visible here.
 */
public final class GoldenSet {

    public static final String DEFAULT_RESOURCE = "evals/golden-set.yaml";

    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private GoldenSet() {
    }

    public static List<GoldenQuestion> load() {
        try (InputStream in = new ClassPathResource(DEFAULT_RESOURCE).getInputStream()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + DEFAULT_RESOURCE, e);
        }
    }

    public static List<GoldenQuestion> parse(String yaml) {
        Object root;
        try (Reader reader = new StringReader(yaml)) {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        if (!(root instanceof Map<?, ?> map) || !(map.get("questions") instanceof List<?> items) || items.isEmpty()) {
            throw new IllegalArgumentException("Golden set needs a non-empty 'questions' list");
        }

        List<GoldenQuestion> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> fields)) {
                throw new IllegalArgumentException("Each question must be a mapping, got: " + item);
            }
            GoldenQuestion q = toQuestion(fields);
            if (!ids.add(q.id())) {
                throw new IllegalArgumentException("Duplicate id: " + q.id());
            }
            questions.add(q);
        }
        return List.copyOf(questions);
    }

    private static GoldenQuestion toQuestion(Map<?, ?> fields) {
        String id = requiredText(fields, "id", "?");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must be kebab-case: " + id);
        }
        Category category = enumValue(Category.class, requiredText(fields, "category", id), id);
        Expect expect = enumValue(Expect.class, requiredText(fields, "expect", id), id);
        String question = requiredText(fields, "question", id);
        List<String> sources = textList(fields, "expectedSources", id);
        List<String> keyPoints = textList(fields, "keyPoints", id);
        List<String> mustNotSay = textList(fields, "mustNotSay", id);

        if (keyPoints.isEmpty()) {
            throw new IllegalArgumentException(id + ": keyPoints must not be empty");
        }
        if (expect == Expect.ANSWER && sources.isEmpty()) {
            throw new IllegalArgumentException(id + ": expect 'answer' needs at least one expectedSources anchor");
        }
        if (expect == Expect.DECLINE && !sources.isEmpty()) {
            throw new IllegalArgumentException(id + ": expect 'decline' must have no expectedSources");
        }
        return new GoldenQuestion(id, category, expect, question, sources, keyPoints, mustNotSay);
    }

    private static String requiredText(Map<?, ?> fields, String key, String id) {
        if (!(fields.get(key) instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(id + ": '" + key + "' is required");
        }
        return s.strip();
    }

    /** Missing key = empty list. Every element must be non-blank text. */
    private static List<String> textList(Map<?, ?> fields, String key, String id) {
        Object value = fields.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(id + ": '" + key + "' must be a list");
        }
        List<String> out = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException(id + ": '" + key + "' entries must be non-blank text, got: " + element);
            }
            out.add(s.strip());
        }
        return List.copyOf(out);
    }

    /** "out-of-scope" → OUT_OF_SCOPE */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String text, String id) {
        try {
            return Enum.valueOf(type, text.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(id + ": unknown " + type.getSimpleName().toLowerCase(Locale.ROOT) + " '" + text + "'");
        }
    }
}
