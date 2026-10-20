package com.tw.docsagent.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tw.docsagent.eval.GoldenQuestion.Category;
import com.tw.docsagent.eval.GoldenQuestion.Expect;
import java.util.List;
import org.junit.jupiter.api.Test;

class GoldenSetTest {

    private static final String VALID = """
            questions:
              - id: service-connection
                category: how-to
                expect: answer
                question: How do I connect tests to a container?
                expectedSources: [testing.testcontainers.service-connections]
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
        assertThat(q.expectedSources()).containsExactly("testing.testcontainers.service-connections");
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
                .hasMessageContaining("expect 'decline' must have no expectedSources");
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
}
