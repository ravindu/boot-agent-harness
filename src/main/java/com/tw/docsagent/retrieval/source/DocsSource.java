package com.tw.docsagent.retrieval.source;

import java.util.Optional;

/** Reads raw documentation files. Real implementation reads GitHub; tests use fixtures. */
public interface DocsSource {

    /**
     * @param page page path without extension, e.g. {@code testing/testcontainers}
     * @return the AsciiDoc source of the page
     */
    String readPage(String page);

    /**
     * @param examplePath path of an {@code include-code} example without extension, e.g.
     *                    {@code testing/testcontainers/serviceconnections/MyIntegrationTests}
     * @return the Java source, or empty if the example doesn't exist
     */
    Optional<String> readExample(String examplePath);
}
