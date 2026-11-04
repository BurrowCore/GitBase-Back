-- V3 — Default roles as reference data (2026-11-04)
--
-- What: makes t_role.role_name unique and inserts ROLE_USER, ROLE_ADMIN, ROLE_CONTRIBUTER.
-- Why:  the roles were created by a startup CommandLineRunner (DataInitializer); reference data
--       belongs to the schema's history, so a fresh database is usable right after migrating.
-- Impact: existing databases already hold these roles once each — ON CONFLICT leaves them untouched.
--       Ids come from t_role_seq, the sequence Hibernate allocates from, so later inserts don't clash.

ALTER TABLE t_role ALTER COLUMN role_name SET NOT NULL;
ALTER TABLE t_role ADD CONSTRAINT uk_role_name UNIQUE (role_name);

INSERT INTO t_role (id, role_name)
SELECT nextval('t_role_seq'), r.name
FROM (VALUES ('ROLE_USER'), ('ROLE_ADMIN'), ('ROLE_CONTRIBUTER')) AS r(name)
ON CONFLICT (role_name) DO NOTHING;
