package com.tw.docsagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocsAgentApplication.class, args);
    }
}
