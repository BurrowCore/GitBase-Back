-- V4 — Name uniqueness the code relies on, and indexes on foreign-key columns (2026-11-04)
--
-- What: (1) guarantees uk_users_name and adds uk_users_lower_name (case-insensitive usernames);
--       (2) adds uk_repository_owner_lower_name — one repository name per owner, case-insensitive;
--       (3) indexes every foreign-key column that had none.
-- Why:  (1) databases built by ddl-auto=update may lack uk_users_name (it was silently skipped when
--       duplicates existed) and never got uk_users_lower_name unless an operator ran the runbook;
--       Hibernate's schema validation does not check constraints, so nothing else would notice.
--       (2) nothing stopped two repositories with the same owner and name; lookups by owner + name then
--       fail ("query did not return a unique result"). Case-insensitive because the repository folder
--       is {owner}/{name}.git and macOS file systems are case-insensitive.
--       (3) ADR-004: lookups by user / author / label must not scan whole tables.
-- Impact: none on clean data. If duplicates exist the migration stops with a message naming them and
--       the runbook (backend/docs/database-schema.md, "Unique usernames"); the application does not
--       start until they are renamed. Plain CREATE INDEX locks writes briefly — fine at current sizes.

DO $$
DECLARE
    dup TEXT;
BEGIN
    SELECT string_agg(n, ', ') INTO dup
    FROM (SELECT lower(name) AS n FROM t_users GROUP BY lower(name) HAVING count(*) > 1) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate usernames (case-insensitive): %. Rename them first — see the '
            '"Unique usernames" runbook in backend/docs/database-schema.md.', dup;
    END IF;

    SELECT string_agg(n, ', ') INTO dup
    FROM (SELECT u.name || '/' || lower(r.repo_name) AS n
          FROM repositories r JOIN t_users u ON u.id = r.user_id
          GROUP BY u.name, lower(r.repo_name) HAVING count(*) > 1) d;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate repository names per owner (case-insensitive): %. Rename or delete '
            'the extra ones first.', dup;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conname = 'uk_users_name' AND conrelid = 't_users'::regclass) THEN
        ALTER TABLE t_users ADD CONSTRAINT uk_users_name UNIQUE (name);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_lower_name ON t_users (lower(name));
CREATE UNIQUE INDEX IF NOT EXISTS uk_repository_owner_lower_name ON repositories (user_id, lower(repo_name));

CREATE INDEX IF NOT EXISTS idx_user_roles_user ON user_roles (user_id);
CREATE INDEX IF NOT EXISTS idx_git_token_user ON git_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_issue_assignee_user ON issue_assignees (user_id);
CREATE INDEX IF NOT EXISTS idx_issue_label_label ON issue_labels (label_id);
CREATE INDEX IF NOT EXISTS idx_issue_author ON issues (author_id);
CREATE INDEX IF NOT EXISTS idx_issue_closed_by ON issues (closed_by_id);
CREATE INDEX IF NOT EXISTS idx_issue_comment_author ON issue_comments (author_id);
CREATE INDEX IF NOT EXISTS idx_issue_dependency_created_by ON issue_dependencies (created_by_id);
CREATE INDEX IF NOT EXISTS idx_pull_request_author ON pull_requests (author_id);
CREATE INDEX IF NOT EXISTS idx_pull_request_merged_by ON pull_requests (merged_by_id);
CREATE INDEX IF NOT EXISTS idx_pr_comment_author ON pull_request_comments (author_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_stage_execution ON pipeline_stage_executions (pipeline_execution_id);
