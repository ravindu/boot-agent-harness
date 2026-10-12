package com.tw.docsagent.retrieval.ingest;

import com.tw.docsagent.retrieval.source.DocsSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Reads hand-written docs from {@code src/test/resources/docs-fixture}, so tests never hit GitHub. */
public class FixtureDocsSource implements DocsSource {

    @Override
    public String readPage(String page) {
        return read("docs-fixture/pages/" + page + ".adoc")
                .orElseThrow(() -> new IllegalArgumentException("No fixture page " + page));
    }

    @Override
    public Optional<String> readExample(String examplePath) {
        return read("docs-fixture/examples/" + examplePath + ".java");
    }

    private Optional<String> read(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
