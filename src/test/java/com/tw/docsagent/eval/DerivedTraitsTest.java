package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.tw.docsagent.eval.DerivedTraits.Chunk;
import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.eval.GoldenQuestion.Trait;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DerivedTraitsTest {

    private static final Chunk PROSE = new Chunk("""
            Testing > Auto-configured Data JPA Tests

            You can use the `@DataJpaTest` annotation from the `spring-boot-data-jpa-test` module.

            NOTE: Transactional tests are not supported with reactive access.""", 0);

    private static final Chunk CODE_AND_TABLE = new Chunk("""
            Testcontainers > Service Connections

            ```java
            @Container

            @ServiceConnection
            static Neo4jContainer neo4j;
            ```

            | `DataRedisConnectionDetails`
            | Containers of type `RedisContainer`""", 2);

    @Test
    void paraphrasedWhenTheQuestionNamesNothingThatIsInTheAnswerChunk() {
        assertThat(derive("Which annotation tests my repositories?", "from the `spring-boot-data-jpa-test` module", PROSE))
                .containsExactly(Trait.PARAPHRASED);
        assertThat(derive("What does @DataJpaTest set up?", "from the `spring-boot-data-jpa-test` module", PROSE))
                .isEmpty();
    }

    @Test
    void deepDetailWhenTheAnswerIsInACalloutOrPastTheFirstChunk() {
        assertThat(derive("Is @DataJpaTest transactional?", "Transactional tests are not supported", PROSE))
                .containsExactly(Trait.DEEP_DETAIL);
        assertThat(derive("How do I use @ServiceConnection?", "Containers of type `RedisContainer`", CODE_AND_TABLE))
                .contains(Trait.DEEP_DETAIL);
    }

    @Test
    void tableOrCodeWhenThePhraseIsInsideAFenceOrATable() {
        assertThat(derive("How do I use @ServiceConnection?", "@ServiceConnection static Neo4jContainer", CODE_AND_TABLE))
                .contains(Trait.TABLE_OR_CODE);
        assertThat(derive("How do I use @ServiceConnection?", "`DataRedisConnectionDetails` | Containers", CODE_AND_TABLE))
                .contains(Trait.TABLE_OR_CODE);
        assertThat(derive("What does @DataJpaTest need?", "from the `spring-boot-data-jpa-test` module", PROSE))
                .doesNotContain(Trait.TABLE_OR_CODE);
    }

    @Test
    void declineQuestionsHaveNoDerivedTraits() {
        GoldenQuestion decline = new GoldenQuestion("kafka", Category.OUT_OF_SCOPE, Expect.DECLINE, List.of(),
                "Kafka?", List.of(), List.of(), List.of("declines"), List.of());

        assertThat(DerivedTraits.derive(decline, List.of())).isEmpty();
    }

    @Test
    void findsAnnotationsClassesMembersMethodsPropertiesAndConstants() {
        assertThat(DerivedTraits.identifiers(
                "Use @DataJpaTest with JdbcTemplate, System.out, isEqualTo, spring.jpa.show-sql and RANDOM_PORT in Docker"))
                .containsExactly("@DataJpaTest", "JdbcTemplate", "System.out", "isEqualTo", "spring.jpa.show-sql", "RANDOM_PORT");
    }

    @Test
    void aFenceWithBlankLinesStaysOneBlock() {
        assertThat(DerivedTraits.blocks(CODE_AND_TABLE.text())).hasSize(3);
    }

    private static Set<Trait> derive(String question, String phrase, Chunk chunk) {
        GoldenQuestion q = new GoldenQuestion("q", Category.HOW_TO, Expect.ANSWER, List.of(), question,
                List.of("anchor"), List.of(phrase), List.of("point"), List.of());
        return DerivedTraits.derive(q, List.of(chunk));
    }
}
