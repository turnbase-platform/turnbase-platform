-- FR-CRED-005 two-role split (secrets-and-configuration.md §4). POSTGRES_USER is
-- turnbase_owner, not turnbase_app: naming the app role there would make it the container's
-- superuser with nothing appearing to be wrong. Runs once, on an empty data directory, as the
-- superuser named by POSTGRES_USER (the only docker-entrypoint-initdb.d hook Postgres offers).
--
-- \getenv (psql 10+) reads the password from the container's own environment so this file can
-- use the exact `:'app_password'` substitution secrets-and-configuration.md §4 specifies —
-- docker-entrypoint-initdb.d invokes plain `psql -f`, with no `-v` flags of its own to supply it.
\getenv app_password TURNBASE_DB_APP_PASSWORD

CREATE ROLE turnbase_app LOGIN PASSWORD :'app_password';
GRANT CONNECT ON DATABASE turnbase TO turnbase_app;
GRANT USAGE, CREATE ON SCHEMA public TO turnbase_app;

-- ADR-0011 §6 / IMP-GATE-13: pg_trgm and btree_gin are not trusted extensions in PostgreSQL 16,
-- so turnbase_app's schema-level CREATE is not enough — only the owner can create them. The
-- baseline migration's own `CREATE EXTENSION IF NOT EXISTS` for all three then succeeds as a
-- no-op.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;
