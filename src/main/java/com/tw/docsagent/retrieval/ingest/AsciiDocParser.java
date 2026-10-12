package com.tw.docsagent.retrieval.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one Spring Boot reference page (Antora AsciiDoc) into sections of plain text.
 *
 * <p>Deliberately not a full AsciiDoc implementation: it handles exactly the constructs the Boot
 * reference uses, so it stays readable. What it does:
 * <ul>
 *   <li>splits at {@code [[anchor]]} + heading pairs, keeping a breadcrumb of parent headings;</li>
 *   <li>replaces {@code include-code::Name[]} with the real Java example (the embedding model and Claude
 *       both need to see the code, not a directive);</li>
 *   <li>turns {@code javadoc:}, {@code xref:}, {@code configprop:} and link macros into plain text;</li>
 *   <li>keeps {@code ----} blocks as Markdown code fences.</li>
 * </ul>
 * Pure function of its input: the example lookup is passed in, so tests need no network.
 */
public class AsciiDocParser {

    private static final Pattern ANCHOR = Pattern.compile("^\\[\\[([^\\]]+)]]\\s*$");
    private static final Pattern HEADING = Pattern.compile("^(=+)\\s+(.+)$");
    private static final Pattern INCLUDE_CODE = Pattern.compile("^include-code::([^\\[]+)\\[[^\\]]*]\\s*$");
    private static final Pattern BLOCK_STYLE = Pattern.compile("^\\[(source|configprops)(?:,\\s*([\\w-]+))?[^\\]]*]\\s*$");
    private static final Pattern ADMONITION_STYLE = Pattern.compile("^\\[(NOTE|TIP|WARNING|IMPORTANT|CAUTION)]\\s*$");

    private static final Pattern JAVADOC = Pattern.compile(
            "javadoc:(?:\\{[\\w-]+}/)?([\\w.$]+)(?:#([^\\[\\s]*))?\\[([^\\]]*)]");
    private static final Pattern CONFIGPROP = Pattern.compile("configprop:([^\\[\\s]+)\\[[^\\]]*]");
    private static final Pattern XREF = Pattern.compile("xref:([^\\[\\s]*)\\[([^\\]]*)]");
    private static final Pattern LINK = Pattern.compile("((?:https?://|\\{url-[\\w-]+})[^\\[\\s]*)\\[([^\\]]*)]");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\{[\\w-]+}");

    /** Result of parsing one page. {@code missingExamples} lists include-code targets that could not be resolved. */
    public record ParsedPage(List<DocSection> sections, int resolvedExamples, List<String> missingExamples) {
    }

    /**
     * @param page          page path, e.g. {@code testing/testcontainers}
     * @param adoc          AsciiDoc source of the page
     * @param exampleSource looks up an include-code example by path, e.g. {@code testing/testcontainers/springbeans/MyTestConfiguration}
     */
    public ParsedPage parse(String page, String adoc, Function<String, Optional<String>> exampleSource) {
        List<DocSection> sections = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int resolved = 0;

        List<String> headings = new ArrayList<>(); // index = heading level - 1
        String pendingAnchor = null;
        String anchor = null;
        String title = null;
        List<String> breadcrumb = List.of();
        StringBuilder body = new StringBuilder();

        boolean inCode = false;
        String codeLanguage = null;

        for (String line : adoc.split("\n", -1)) {
            line = stripTrailing(line);

            if (inCode) {
                if (line.equals("----") || line.equals("....")) {
                    body.append("```\n");
                    inCode = false;
                } else {
                    body.append(line).append('\n');
                }
                continue;
            }

            Matcher m;
            if ((m = BLOCK_STYLE.matcher(line)).matches()) {
                codeLanguage = m.group(2) != null ? m.group(2) : "";
                continue;
            }
            if (line.equals("----") || line.equals("....")) {
                body.append("```").append(codeLanguage == null ? "" : codeLanguage).append('\n');
                inCode = true;
                codeLanguage = null;
                continue;
            }
            if ((m = ANCHOR.matcher(line)).matches()) {
                pendingAnchor = m.group(1);
                continue;
            }
            if ((m = HEADING.matcher(line)).matches() && (pendingAnchor != null || anchor != null)) {
                addSection(sections, page, anchor, title, breadcrumb, body);
                int level = m.group(1).length();
                while (headings.size() >= level) {
                    headings.removeLast();
                }
                title = cleanInline(m.group(2));
                headings.add(title);
                // A heading without its own anchor shares the enclosing anchor, so its citation URL still works
                anchor = pendingAnchor != null ? pendingAnchor : anchor;
                pendingAnchor = null;
                breadcrumb = List.copyOf(headings);
                body = new StringBuilder();
                continue;
            }
            if ((m = INCLUDE_CODE.matcher(line)).matches() && anchor != null) {
                String path = examplePath(anchor, m.group(1).trim());
                Optional<String> example = exampleSource.apply(path);
                if (example.isPresent()) {
                    body.append("```java\n").append(trimJavaExample(example.get())).append("```\n");
                    resolved++;
                } else {
                    missing.add(path);
                }
                continue;
            }
            if ((m = ADMONITION_STYLE.matcher(line)).matches()) {
                body.append(m.group(1)).append(":\n");
                continue;
            }
            if (line.equals("====") || line.equals("****") || line.startsWith("//")
                    || line.startsWith("ifdef::") || line.startsWith("ifndef::") || line.startsWith("endif::")
                    || (line.startsWith(":") && anchor == null)) {
                continue;
            }
            body.append(cleanInline(line)).append('\n');
        }
        addSection(sections, page, anchor, title, breadcrumb, body);
        return new ParsedPage(sections, resolved, missing);
    }

    /**
     * Where the Boot build keeps include-code examples: the section anchor turned into a package,
     * e.g. {@code testing.testcontainers.spring-beans} + {@code MyTestConfiguration}
     * → {@code testing/testcontainers/springbeans/MyTestConfiguration}.
     */
    static String examplePath(String anchor, String name) {
        return anchor.replace("-", "").replace('.', '/') + "/" + name;
    }

    /** Resolves AsciiDoc inline macros to the text a reader would see on the docs site. */
    static String cleanInline(String line) {
        String out = JAVADOC.matcher(line).replaceAll(m -> {
            String linkText = m.group(3);
            if (!linkText.isEmpty() && !linkText.startsWith("format=")) {
                return Matcher.quoteReplacement(linkText);
            }
            String fqcn = m.group(1);
            String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1).replace('$', '.');
            String member = m.group(2) != null && !m.group(2).isEmpty() ? "." + m.group(2) : "";
            String prefix = linkText.equals("format=annotation") ? "@" : "";
            return Matcher.quoteReplacement("`" + prefix + simpleName + member + "`");
        });
        out = CONFIGPROP.matcher(out).replaceAll(m -> Matcher.quoteReplacement("`" + m.group(1) + "`"));
        out = XREF.matcher(out).replaceAll(m -> {
            if (!m.group(2).isEmpty()) {
                return Matcher.quoteReplacement(m.group(2));
            }
            String target = m.group(1);
            int hash = target.indexOf('#');
            return Matcher.quoteReplacement(hash >= 0 ? target.substring(hash + 1) : target);
        });
        out = LINK.matcher(out).replaceAll(m -> Matcher.quoteReplacement(
                m.group(2).isEmpty() ? m.group(1) : m.group(2)));
        out = ATTRIBUTE.matcher(out).replaceAll("");
        // AsciiDoc curved quotes: "`How-to Guides`" -> "How-to Guides"
        return out.replace("\"`", "\"").replace("`\"", "\"");
    }

    /** Drops the license header and package line: they cost tokens and say nothing about Spring Boot. */
    static String trimJavaExample(String source) {
        String s = source.strip();
        if (s.startsWith("/*")) {
            int end = s.indexOf("*/");
            if (end >= 0) {
                s = s.substring(end + 2).strip();
            }
        }
        if (s.startsWith("package ")) {
            int end = s.indexOf('\n');
            s = end >= 0 ? s.substring(end + 1).strip() : "";
        }
        return s + "\n";
    }

    private static void addSection(List<DocSection> sections, String page, String anchor, String title,
                                   List<String> breadcrumb, StringBuilder body) {
        if (anchor == null || title == null) {
            return;
        }
        String text = body.toString().replaceAll("\n{3,}", "\n\n").strip();
        sections.add(new DocSection(page, anchor, title, breadcrumb, text));
    }

    private static String stripTrailing(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1).stripTrailing() : line.stripTrailing();
    }
}
