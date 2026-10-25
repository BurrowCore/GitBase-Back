package org.os.gitbase.git.service;

import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.repository.IssueRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Which issues a pull request will close when merged: the closing-keyword references in its title
 * and description that name an actual issue. Because issues and pull requests share one number
 * sequence, "Fixes #4" may name a pull request — those are dropped here.
 */
@Component
public class ClosingIssueResolver {

    private final ClosingKeywordParser parser;
    private final IssueRepository issueRepository;

    public ClosingIssueResolver(ClosingKeywordParser parser, IssueRepository issueRepository) {
        this.parser = parser;
        this.issueRepository = issueRepository;
    }

    /** Referenced issue numbers, ascending; one query however many references there are. */
    public List<Integer> closingIssues(RepositoryGit repository, String title, String description) {
        Set<Integer> referenced = parser.closedIssueNumbers(title, description);
        if (referenced.isEmpty()) {
            return List.of();
        }
        return issueRepository.findExistingNumbers(repository, referenced);
    }
}
