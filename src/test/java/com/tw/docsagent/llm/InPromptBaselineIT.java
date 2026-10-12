package com.tw.docsagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.agent.CostCalculator;
import com.tw.docsagent.agent.TokenUsage;
import com.tw.docsagent.config.AnthropicProperties;
import com.tw.docsagent.retrieval.ingest.AsciiDocParser;
import com.tw.docsagent.retrieval.ingest.DocSection;
import com.tw.docsagent.retrieval.source.DocsSource;
import com.tw.docsagent.retrieval.source.DocsSourceProperties;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The "no RAG" baseline the roadmap asks for before any retrieval: all ten pages (~45K tokens) go
 * into a cached system prompt and Claude answers from that. RAG has to beat this on quality or cost (M2).
 *
 * <p>Shows prompt caching: the first call writes the docs to the cache
 * ({@code cache_creation_input_tokens}), the second, identical prefix reads them
 * ({@code cache_read_input_tokens}) at a tenth of the input price.
 *
 * <p>Costs roughly $0.11 + $0.01. Run with: {@code ./mvnw verify -Plive -Dit.test=InPromptBaselineIT}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InPromptBaselineIT {

    private static final String QUESTION = "How do I use @ServiceConnection with Testcontainers? Cite the section anchor.";

    @Autowired
    LlmGateway llm;

    @Autowired
    DocsSource docsSource;

    @Autowired
    DocsSourceProperties docs;

    @Autowired
    AnthropicProperties anthropic;

    @Autowired
    CostCalculator costCalculator;

    @Test
    void answersFromTheWholeDocSubsetInACachedPrompt() {
        String allDocs = renderAllPages();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(anthropic.agentModel())
                .maxTokens(anthropic.agentMaxTokens())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(anthropic.agentEffort())).build())
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text("Answer questions about Spring Boot " + docs.bootVersion()
                                        + " using only the documentation below. Cite section anchors like [testing.testcontainers].")
                                .build(),
                        // cache_control marks the end of the cached prefix: everything up to here is cached
                        TextBlockParam.builder()
                                .text(allDocs)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()))
                .addUserMessage(QUESTION)
                .build();

        Message first = llm.send(params);
        Message second = llm.send(params);

        report("first (cache write)", first);
        report("second (cache read)", second);
        assertThat(first.usage().cacheCreationInputTokens().orElse(0L) + first.usage().cacheReadInputTokens().orElse(0L))
                .isPositive();
        assertThat(second.usage().cacheReadInputTokens()).hasValueSatisfying(tokens -> assertThat(tokens).isPositive());
        assertThat(text(second)).contains("testing.testcontainers.service-connections");
    }

    private String renderAllPages() {
        AsciiDocParser parser = new AsciiDocParser();
        return docs.pages().stream()
                .flatMap(page -> parser.parse(page, docsSource.readPage(page), docsSource::readExample).sections().stream())
                .map(this::render)
                .collect(Collectors.joining("\n\n"));
    }

    private String render(DocSection section) {
        return "<section anchor=\"" + section.anchor() + "\" title=\"" + section.breadcrumbText() + "\">\n"
                + section.body() + "\n</section>";
    }

    private void report(String label, Message message) {
        TokenUsage usage = TokenUsage.of(message.usage());
        System.out.printf("baseline %s: input=%d cacheWrite=%d cacheRead=%d output=%d costUsd=%s%n",
                label, usage.input(), usage.cacheWrite(), usage.cacheRead(), usage.output(),
                costCalculator.cost(anthropic.agentModel(), usage));
    }

    private static String text(Message message) {
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining());
    }
}
