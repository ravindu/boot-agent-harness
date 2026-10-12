package com.tw.docsagent.agent;

import com.tw.docsagent.retrieval.source.DocsSourceProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The agent's system prompt, from {@code prompts/agent-system.txt}. Rendered once at startup, so it
 * is byte-identical on every request (a precondition for prompt caching, M3).
 */
@Component
public class SystemPrompt {

    private final String text;

    public SystemPrompt(DocsSourceProperties docs) {
        try {
            String template = new ClassPathResource("prompts/agent-system.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
            this.text = template.replace("{bootVersion}", docs.bootVersion()).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String text() {
        return text;
    }
}
