-- V2 — Drop tables no entity maps any more (2026-11-04)
--
-- What: drops commits, commit_parents, branches, tags and t_refresh_tokens.
-- Why:  commits / branches / tags duplicated git state in the database, which DECISION-007 forbids
--       (git is the source of truth; reads go live to JGit). t_refresh_tokens held refresh tokens in
--       plaintext and was replaced by auth_sessions + auth_refresh_tokens (DECISION-016).
-- Impact: no code reads these tables. Old refresh tokens were already refused, so no one is signed
--       out by this. IF EXISTS: databases created from V1 never had them.
-- Guard: baseline-on-migrate marks any non-empty schema as version 1. If the datasource points at a
--       database that is not GitBase's, stop here instead of dropping someone else's tables.

DO $$
BEGIN
    IF to_regclass('t_users') IS NULL OR to_regclass('repositories') IS NULL THEN
        RAISE EXCEPTION 'This schema is not a GitBase database (no t_users / repositories table). '
            'Check spring.datasource.url — refusing to drop tables.';
    END IF;
END $$;

DROP TABLE IF EXISTS commit_parents CASCADE;
DROP TABLE IF EXISTS commits CASCADE;
DROP TABLE IF EXISTS tags CASCADE;
DROP TABLE IF EXISTS branches CASCADE;
DROP TABLE IF EXISTS t_refresh_tokens CASCADE;
