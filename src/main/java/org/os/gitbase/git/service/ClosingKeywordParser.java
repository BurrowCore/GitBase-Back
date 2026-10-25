package org.os.gitbase.git.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds "closes #12"-style references in pull-request text.
 *
 * <p>Keywords: {@code close, closes, closed,
 * fix, fixes, fixed, resolve, resolves, resolved}, case-insensitive, preceded by start of text,
 * whitespace, {@code (} or {@code [}, then an optional {@code :} and exactly one space before
 * {@code #N}. So {@code Fixes #3} and {@code (closes: #3)} match; {@code closes:#3},
 * {@code closes  #3} and {@code closes owner/repo#3} do not.
 *
 * <p>Only same-repository {@code #N} references are recognised — cross-repository
 * {@code owner/repo#N} and URL references are a later addition. There are no "reopen"
 * keywords.
 */
@Component
public class ClosingKeywordParser {

    /** Bounds the work one pull request can trigger (ADR-004). */
    static final int MAX_REFERENCES = 50;

    /**
     * The number must end the reference: end of text, whitespace, a closing
     * bracket or quote, or punctuation followed by whitespace/end — so {@code fixes #12.} matches
     * but {@code fixes #12.5} and {@code fixes #12-foo} do not.
     */
    private static final Pattern CLOSING_REFERENCE = Pattern.compile(
            "(?i)(?:^|[\\s(\\[])(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?):? #(\\d{1,9})"
                    + "(?=$|[\\s)\\]'\"]|[.,;:!?](?:\\s|$))");

    /** Fenced code blocks, inline code and HTML comments — quoted examples, not instructions. */
    private static final Pattern NON_PROSE = Pattern.compile(
            "(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)|<!--.*?(?:-->|$)|`[^`\\n]*`");

    /** Issue numbers referenced with a closing keyword, in order of first appearance. */
    public Set<Integer> closedIssueNumbers(String... texts) {
        Set<Integer> numbers = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // A PR template saying "<!-- e.g. Fixes #1 -->" must not close #1 on every merge.
            Matcher matcher = CLOSING_REFERENCE.matcher(NON_PROSE.matcher(text).replaceAll(" "));
            while (matcher.find() && numbers.size() < MAX_REFERENCES) {
                numbers.add(Integer.parseInt(matcher.group(1)));
            }
        }
        return numbers;
    }
}
