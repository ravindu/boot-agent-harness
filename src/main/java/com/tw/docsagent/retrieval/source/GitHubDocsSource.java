package com.tw.docsagent.retrieval.source;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Reads docs from raw.githubusercontent.com at the pinned tag. No API token needed. */
@Component
class GitHubDocsSource implements DocsSource {

    private static final String PAGES_DIR = "documentation/spring-boot-docs/src/docs/antora/modules/reference/pages";
    private static final String EXAMPLES_DIR = "documentation/spring-boot-docs/src/main/java/org/springframework/boot/docs";

    private final RestClient restClient;
    private final DocsSourceProperties properties;

    GitHubDocsSource(DocsSourceProperties properties) {
        // Plain spring-web builder: Boot 4 auto-configures RestClient.Builder only with spring-boot-starter-restclient
        this.restClient = RestClient.builder().baseUrl(properties.rawBaseUrl()).build();
        this.properties = properties;
    }

    @Override
    public String readPage(String page) {
        return restClient.get()
                .uri(path(PAGES_DIR, page + ".adoc"))
                .retrieve()
                .body(String.class);
    }

    @Override
    public Optional<String> readExample(String examplePath) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(path(EXAMPLES_DIR, examplePath + ".java"))
                    .retrieve()
                    .body(String.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // Built as a plain string: as a URI template variable, every "/" in the path would be encoded as %2F
    private String path(String dir, String file) {
        return "/" + properties.tag() + "/" + dir + "/" + file;
    }
}
