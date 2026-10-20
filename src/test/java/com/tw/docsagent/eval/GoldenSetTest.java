package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import com.tw.docsagent.eval.GoldenQuestion.Trait;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoldenSetTest {

    private static final String VALID = """
            questions:
              - id: service-connection
                category: how-to
                expect: answer
                traits: [table-or-code]
                question: How do I connect tests to a container?
                expectedSources: [testing.testcontainers.service-connections]
                expectedText: ['@ServiceConnection static Neo4jContainer']
                keyPoints: [Use @ServiceConnection]
            """;

    @Test
    void theCheckedInGoldenSetIsValid() {
        List<GoldenQuestion> questions = GoldenSet.load();

        assertThat(questions).isNotEmpty();
        assertThat(questions).extracting(GoldenQuestion::id).doesNotHaveDuplicates();
        assertThat(questions).anySatisfy(q -> assertThat(q.expect()).isEqualTo(Expect.DECLINE));
    }

    @Test
    void parsesAllFieldsAndKebabCaseEnums() {
        GoldenQuestion q = GoldenSet.parse(VALID).getFirst();

        assertThat(q.id()).isEqualTo("service-connection");
        assertThat(q.category()).isEqualTo(Category.HOW_TO);
        assertThat(q.expect()).isEqualTo(Expect.ANSWER);
        assertThat(q.traits()).containsExactly(Trait.TABLE_OR_CODE);
        assertThat(q.expectedSources()).containsExactly("testing.testcontainers.service-connections");
        assertThat(q.expectedText()).containsExactly("@ServiceConnection static Neo4jContainer");
        assertThat(q.keyPoints()).containsExactly("Use @ServiceConnection");
        assertThat(q.mustNotSay()).isEmpty();
    }

    @Test
    void answerQuestionsNeedAnExpectedSource() {
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace(
                "expectedSources: [testing.testcontainers.service-connections]", "expectedSources: []")))
                .hasMessageContaining("service-connection: expect 'answer' needs at least one expectedSources anchor");
    }

    @Test
    void declineQuestionsMustNotHaveExpectedSources() {
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("expect: answer", "expect: decline")))
                .hasMessageContaining("expect 'decline' must have no expectedSources or expectedText");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("expect: answer", "expect: decline")
                .replace("expectedSources: [testing.testcontainers.service-connections]", "expectedSources: []")))
                .hasMessageContaining("expect 'decline' must have no expectedSources or expectedText");
    }

    @Test
    void rejectsDuplicateIds() {
        String twice = VALID + VALID.substring(VALID.indexOf("  - id"));

        assertThatThrownBy(() -> GoldenSet.parse(twice)).hasMessage("Duplicate id: service-connection");
    }

    @Test
    void rejectsUnknownCategoryMissingFieldsAndBadIds() {
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("how-to", "trivia")))
                .hasMessage("service-connection: unknown category 'trivia'");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("    keyPoints: [Use @ServiceConnection]\n", "")))
                .hasMessage("service-connection: keyPoints must not be empty");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("id: service-connection", "id: Service Connection")))
                .hasMessage("id must be kebab-case: Service Connection");
        assertThatThrownBy(() -> GoldenSet.parse("questions: []"))
                .hasMessage("Golden set needs a non-empty 'questions' list");
    }

    @Test
    void traitsAreRequiredButMayBeEmpty() {
        assertThat(GoldenSet.parse(VALID.replace("traits: [table-or-code]", "traits: []")).getFirst().traits()).isEmpty();
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("    traits: [table-or-code]\n", "")))
                .hasMessage("service-connection: 'traits' is required (use [] for none)");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("[table-or-code]", "[tricky]")))
                .hasMessage("service-connection: unknown trait 'tricky'");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("[table-or-code]", "[paraphrased, paraphrased]")))
                .hasMessage("service-connection: duplicate traits [PARAPHRASED, PARAPHRASED]");
    }

    @Test
    void multiHopMeansTwoOrMoreExpectedTextPhrases() {
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("[table-or-code]", "[multi-hop]")))
                .hasMessageContaining("'multi-hop' must be set exactly when there are two or more expectedText phrases");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace(
                "['@ServiceConnection static Neo4jContainer']", "['@ServiceConnection', 'Neo4jContainer']")))
                .hasMessageContaining("'multi-hop' must be set exactly when there are two or more expectedText phrases");
    }

    @Test
    void handTaggedTraitsNeedTheFieldsThatMakeThemCheckable() {
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("[table-or-code]", "[boot3-habit]")))
                .hasMessage("service-connection: 'boot3-habit' needs a mustNotSay item naming the Boot 3 answer");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("[table-or-code]", "[docs-gap]")))
                .hasMessageContaining("'docs-gap' is for decline questions");
        assertThatThrownBy(() -> GoldenSet.parse(VALID.replace("[table-or-code]", "[partial-coverage]")
                .replace("expect: answer", "expect: decline")
                .replace("    expectedSources: [testing.testcontainers.service-connections]\n", "")
                .replace("    expectedText: ['@ServiceConnection static Neo4jContainer']\n", "")))
                .hasMessage("service-connection: 'partial-coverage' is for answer questions");
    }
}
