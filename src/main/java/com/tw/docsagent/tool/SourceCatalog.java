package com.tw.docsagent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Labels every chunk shown to Claude during one run as {@code S1, S2, ...}.
 *
 * <p>The system prompt asks Claude to cite with these labels. Because only the harness hands them
 * out, a cited label that is not in the catalog proves an invented citation. The same chunk retrieved
 * twice keeps its first label, so citations stay stable across searches.
 */
public class SourceCatalog {

    /** One retrieved chunk as Claude saw it. */
    public record Source(String label, String chunkId, String title, String url) {
    }

    private final Map<String, Source> byChunkId = new LinkedHashMap<>();
    private final Map<String, Source> byLabel = new LinkedHashMap<>();

    public Source register(String chunkId, String title, String url) {
        return byChunkId.computeIfAbsent(chunkId, id -> {
            Source source = new Source("S" + (byChunkId.size() + 1), id, title, url);
            byLabel.put(source.label(), source);
            return source;
        });
    }

    /**
     * Rebuilds the catalog of a paused run from its stored sources. Labels come back as they were
     * because they are handed out in the same order.
     */
    public static SourceCatalog restore(List<Source> sources) {
        SourceCatalog catalog = new SourceCatalog();
        for (Source source : sources) {
            Source restored = catalog.register(source.chunkId(), source.title(), source.url());
            if (!restored.label().equals(source.label())) {
                throw new IllegalStateException("Stored source " + source.label() + " restored as " + restored.label());
            }
        }
        return catalog;
    }

    public Optional<Source> resolve(String label) {
        return Optional.ofNullable(byLabel.get(label));
    }

    public List<Source> all() {
        return new ArrayList<>(byLabel.values());
    }
}
