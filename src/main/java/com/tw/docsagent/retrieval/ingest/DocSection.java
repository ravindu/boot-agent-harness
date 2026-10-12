package com.tw.docsagent.retrieval.ingest;

import java.util.List;

/**
 * One section of a reference page: the text between one {@code [[anchor]]} heading and the next.
 *
 * @param page       page path without extension, e.g. {@code testing/testcontainers}
 * @param anchor     AsciiDoc anchor, e.g. {@code testing.testcontainers.service-connections}; becomes the citation target
 * @param title      section heading
 * @param breadcrumb headings from the page title down to this section
 * @param body       cleaned text: macros resolved, code blocks as Markdown fences, examples inlined
 */
public record DocSection(String page, String anchor, String title, List<String> breadcrumb, String body) {

    public String breadcrumbText() {
        return String.join(" > ", breadcrumb);
    }
}
