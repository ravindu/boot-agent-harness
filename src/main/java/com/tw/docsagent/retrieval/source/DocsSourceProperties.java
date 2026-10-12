package com.tw.docsagent.retrieval.source;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the Spring Boot reference docs come from.
 *
 * @param rawBaseUrl  raw file host for the spring-boot repository
 * @param tag         git tag the docs are pinned to, e.g. {@code v4.1.1}
 * @param bootVersion version stored as chunk metadata (used for filtering from M4)
 * @param siteBaseUrl published docs, used to build citation URLs
 * @param pages       reference pages to ingest, relative to the Antora {@code pages} directory
 */
@Validated
@ConfigurationProperties("docsagent.docs")
public record DocsSourceProperties(
        @NotBlank String rawBaseUrl,
        @NotBlank String tag,
        @NotBlank String bootVersion,
        @NotBlank String siteBaseUrl,
        @NotEmpty List<String> pages) {

    /** Citation URL for a section, e.g. {@code …/4.1/reference/testing/testcontainers.html#testing.testcontainers}. */
    public String sectionUrl(String page, String anchor) {
        return siteBaseUrl + "/" + page + ".html#" + anchor;
    }
}
