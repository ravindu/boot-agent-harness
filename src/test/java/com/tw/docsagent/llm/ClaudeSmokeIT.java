package com.tw.docsagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.tw.docsagent.TestcontainersConfiguration;
import com.tw.docsagent.config.AnthropicProperties;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Live call to the Claude API: proves wiring, auth and response shape, and that usage numbers
 * (needed for cost tracing in M1) are populated. Costs a fraction of a cent on the helper model.
 * Run with: ./mvnw verify -Plive
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClaudeSmokeIT {

    @Autowired
    LlmGateway llmGateway;

    @Autowired
    AnthropicProperties properties;

    @Test
    void helperModelAnswersAndReportsUsage() {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.helperModel())
                .maxTokens(1024L)
                // Claude 5.5 models always think; effort controls how much. LOW keeps this cheap.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("Reply with exactly one word: pong")
                .build();

        Message message = llmGateway.send(params);

        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .collect(Collectors.joining());

        assertThat(message.stopReason()).contains(StopReason.END_TURN);
        assertThat(text).containsIgnoringCase("pong");
        assertThat(message.usage().inputTokens()).isPositive();
        assertThat(message.usage().outputTokens()).isPositive();
    }
}
