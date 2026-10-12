package com.tw.docsagent.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AnthropicClientConfig {

    @Bean(destroyMethod = "close")
    AnthropicClient anthropicClient(AnthropicProperties properties) {
        return AnthropicOkHttpClient.builder()
                .fromEnv() // ANTHROPIC_API_KEY (and ANTHROPIC_BASE_URL if set)
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                .build();
    }
}
