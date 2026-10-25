package org.os.gitbase.git.repository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.os.gitbase.git.entity.Issue;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.entity.enums.IssueState;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Composable filters for the issue list.
 *
 * <p>Built with the Criteria API rather than one JPQL query full of {@code (:param IS NULL OR …)}
 * guards, for two reasons:
 * <ul>
 *   <li><b>Correctness.</b> A null bound into {@code lower(concat('%', :search, '%'))} has no
 *       inferable type, so PostgreSQL falls back to {@code bytea} and the statement dies with
 *       <em>function lower(bytea) does not exist</em> — even though the guard would have
 *       short-circuited it, because the expression is still typed at plan time.</li>
 *   <li><b>Plan quality.</b> {@code IS NULL OR} predicates are opaque to the planner and stop it
 *       using the {@code (repository_id, state)} index. Omitting an unused filter entirely leaves
 *       a query the planner can optimise (ADR-004).</li>
 * </ul>
 */
public final class IssueSpecifications {

    private IssueSpecifications() {
    }

    public static Specification<Issue> inRepository(RepositoryGit repository) {
        return (root, query, cb) -> cb.equal(root.get("repository"), repository);
    }

    /** No-op when {@code state} is null, i.e. "all states". */
    public static Specification<Issue> hasState(IssueState state) {
        if (state == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("state"), state);
    }

    public static Specification<Issue> hasLabel(String label) {
        if (!StringUtils.hasText(label)) {
            return null;
        }
        return (root, query, cb) -> {
            // A join onto a to-many can multiply rows, so de-duplicate. Skipped for the count
            // query, where Hibernate rejects distinct on a count projection.
            if (query != null && query.getResultType() != Long.class) {
                query.distinct(true);
            }
            Join<Object, Object> labels = root.join("labels");
            return cb.equal(labels.get("name"), label);
        };
    }

    public static Specification<Issue> hasAssignee(String assignee) {
        if (!StringUtils.hasText(assignee)) {
            return null;
        }
        return (root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class) {
                query.distinct(true);
            }
            Join<Object, Object> assignees = root.join("assignees");
            return cb.equal(assignees.get("name"), assignee);
        };
    }

    /** Case-insensitive match against title or body. */
    public static Specification<Issue> matches(String search) {
        if (!StringUtils.hasText(search)) {
            return null;
        }
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate title = cb.like(cb.lower(root.get("title")), pattern);
            Predicate body = cb.like(cb.lower(cb.coalesce(root.get("body"), "")), pattern);
            return cb.or(title, body);
        };
    }
}
