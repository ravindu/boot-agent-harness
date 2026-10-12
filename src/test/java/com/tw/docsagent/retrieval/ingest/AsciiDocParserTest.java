package com.tw.docsagent.retrieval.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AsciiDocParserTest {

    private final FixtureDocsSource source = new FixtureDocsSource();
    private final AsciiDocParser parser = new AsciiDocParser();

    private AsciiDocParser.ParsedPage parseFixture() {
        return parser.parse("testing/testcontainers", source.readPage("testing/testcontainers"), source::readExample);
    }

    @Test
    void splitsAtAnchoredHeadingsWithBreadcrumbs() {
        List<DocSection> sections = parseFixture().sections();

        assertThat(sections).extracting(DocSection::anchor).containsExactly(
                "testing.testcontainers",
                "testing.testcontainers.service-connections",
                "testing.testcontainers.dynamic-properties");
        assertThat(sections.get(2).breadcrumb())
                .containsExactly("Testcontainers", "Service Connections", "Dynamic Properties");
        assertThat(sections).allSatisfy(s -> assertThat(s.page()).isEqualTo("testing/testcontainers"));
    }

    @Test
    void inlinesIncludeCodeExamplesWithoutLicenseHeaderAndReportsMissingOnes() {
        AsciiDocParser.ParsedPage page = parseFixture();
        String body = page.sections().get(1).body();

        assertThat(body).contains("```java\n@SpringBootTest\n@Testcontainers\nclass MyIntegrationTests {");
        assertThat(body).doesNotContain("License header", "package org.springframework");
        assertThat(page.resolvedExamples()).isEqualTo(1);
        assertThat(page.missingExamples())
                .containsExactly("testing/testcontainers/serviceconnections/DoesNotExist");
    }

    @Test
    void resolvesMacrosToReadableText() {
        String body = parseFixture().sections().get(1).body();

        assertThat(body)
                .contains("Add the `@ServiceConnection` annotation to the container field.")
                .contains("See `spring.datasource.url` and dynamic properties.")
                .doesNotContain("javadoc:", "configprop:", "xref:");
        assertThat(parseFixture().sections().get(0).body())
                .isEqualTo("The Testcontainers library manages services running inside Docker containers.");
    }

    @Test
    void keepsSourceBlocksAsCodeFencesAndAdmonitions() {
        String body = parseFixture().sections().get(2).body();

        assertThat(body).contains("```yaml\nspring:\n  datasource:\n    url: jdbc:postgresql://localhost/test\n```");
        assertThat(body).contains("NOTE:\nPrefer \"service connections\" where possible.");
        assertThat(body).doesNotContain("====", "----");
    }

    @Test
    void cleanInlineHandlesJavadocVariants() {
        assertThat(AsciiDocParser.cleanInline("javadoc:org.springframework.context.ApplicationContext[]"))
                .isEqualTo("`ApplicationContext`");
        assertThat(AsciiDocParser.cleanInline(
                "javadoc:{url-testcontainers-mongodb-javadoc}/org.testcontainers.mongodb.MongoDBContainer[]"))
                .isEqualTo("`MongoDBContainer`");
        assertThat(AsciiDocParser.cleanInline("javadoc:org.testcontainers.oracle.OracleContainer[OracleContainer (free)]"))
                .isEqualTo("OracleContainer (free)");
        assertThat(AsciiDocParser.cleanInline("xref:how-to:data-access.adoc#howto.data-access.configure-custom-datasource[]"))
                .isEqualTo("howto.data-access.configure-custom-datasource");
    }

    @Test
    void examplePathFollowsTheBootDocsPackageConvention() {
        assertThat(AsciiDocParser.examplePath("testing.testcontainers.spring-beans", "MyTestConfiguration"))
                .isEqualTo("testing/testcontainers/springbeans/MyTestConfiguration");
    }
}
