package com.dbbaskette.issuebot.service.workflow;

import com.dbbaskette.issuebot.service.review.CodeReviewResult.ReviewFinding;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BacklogServiceTest {

    @Test
    void dedupKeyIsStableAndIgnoresLineNumbers() {
        ReviewFinding a = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", "extract constant");
        ReviewFinding b = new ReviewFinding("medium", "code_quality", "src/Foo.java", 99, "Magic number 7", "different suggestion");
        assertThat(BacklogService.dedupKey(a)).isEqualTo(BacklogService.dedupKey(b));
    }

    @Test
    void differentFindingsGetDifferentKeys() {
        ReviewFinding a = new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null);
        ReviewFinding b = new ReviewFinding("medium", "security", "src/Foo.java", 42, "Magic number 7", null);
        assertThat(BacklogService.dedupKey(a)).isNotEqualTo(BacklogService.dedupKey(b));
    }

    @Test
    void mergeAppendsOnlyNewFindingsAndUpdatesKeyStore() {
        String existingBody = """
                Findings from automated reviews.

                - [ ] **[MEDIUM — code_quality]** `src/Foo.java:42` — Magic number 7 (from #10 / PR #11)

                <!-- issuebot-keys: %s -->
                """.formatted(BacklogService.dedupKey(
                        new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null)));

        List<ReviewFinding> incoming = List.of(
                new ReviewFinding("medium", "code_quality", "src/Foo.java", 42, "Magic number 7", null), // dup
                new ReviewFinding("medium", "test_coverage", "src/Bar.java", 5, "No test for null path", null));

        BacklogService.MergeResult result = BacklogService.merge(existingBody, incoming, 12, 13);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.body()).contains("No test for null path");
        assertThat(result.body()).containsOnlyOnce("Magic number 7");
        assertThat(result.body()).contains("issuebot-keys:");
    }

    @Test
    void mergePrunesOldestCheckedItemsBeyondCap() {
        StringBuilder body = new StringBuilder("Findings.\n\n");
        for (int i = 0; i < 55; i++) {
            body.append("- [x] **[MEDIUM — code_quality]** `f").append(i).append(".java:1` — done item ").append(i)
                .append(" (from #1 / PR #2)\n");
        }
        body.append("\n<!-- issuebot-keys: -->\n");
        BacklogService.MergeResult result = BacklogService.merge(body.toString(),
                List.of(new ReviewFinding("medium", "code_quality", "new.java", 1, "fresh", null)), 3, 4);
        long items = result.body().lines().filter(l -> l.startsWith("- [")).count();
        assertThat(items).isLessThanOrEqualTo(50);
        assertThat(result.body()).contains("fresh");
    }
}
