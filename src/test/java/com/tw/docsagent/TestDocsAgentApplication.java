package com.tw.docsagent;

import org.springframework.boot.SpringApplication;

/** Runs the app locally against a Testcontainers database instead of compose.yaml. */
public class TestDocsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.from(DocsAgentApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
