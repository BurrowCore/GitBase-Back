package org.os.gitbase.git.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The closing-keyword grammar; same-repository references only. */
class ClosingKeywordParserTest {

    private final ClosingKeywordParser parser = new ClosingKeywordParser();

    @ParameterizedTest
    @ValueSource(strings = {"close #12", "closes #12", "closed #12", "fix #12", "fixes #12", "fixed #12",
            "resolve #12", "resolves #12", "resolved #12", "FIXES #12", "Closes: #12", "(fixes #12)",
            "[resolves #12]", "This PR\nfixes #12 at last"})
    void recognisesEveryKeywordForm(String text) {
        assertThat(parser.closedIssueNumbers(text)).containsExactly(12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"closes:#12", "closes  #12", "closes owner/repo#12", "prefixes #12",
            "see #12", "fixes#12", "unfixes #12", "closes #abc", "fixes #12.5", "fixes #12-foo",
            "<!-- e.g. Fixes #12 -->", "```\nfixes #12\n```", "use `fixes #12` to link", "~~~\ncloses #12\n~~~"})
    void ignoresAnythingElse(String text) {
        assertThat(parser.closedIssueNumbers(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixes #12.", "fixes #12, and more", "it fixes #12\" he said", "fixes #12!"})
    void acceptsReferenceTerminators(String text) {
        assertThat(parser.closedIssueNumbers(text)).containsExactly(12);
    }

    @Test
    void stillReadsProseAroundAQuotedExample() {
        assertThat(parser.closedIssueNumbers("<!-- Fixes #1 -->\nThis PR fixes #7.")).containsExactly(7);
    }

    @Test
    void collectsAcrossTitleAndBodyInOrderWithoutDuplicates() {
        assertThat(parser.closedIssueNumbers("Fix login (fixes #4)", "Closes #2, resolves #4 and fixes #9"))
                .containsExactly(4, 2, 9);
    }

    @Test
    void isBounded() {
        String many = "fixes #1 ".repeat(1).concat(java.util.stream.IntStream.rangeClosed(2, 200)
                .mapToObj(n -> "fixes #" + n).reduce("", (a, b) -> a + " " + b));
        assertThat(parser.closedIssueNumbers(many)).hasSize(ClosingKeywordParser.MAX_REFERENCES);
    }

    @Test
    void nullAndBlankAreFine() {
        assertThat(parser.closedIssueNumbers(null, "", "  ")).isEmpty();
    }
}
