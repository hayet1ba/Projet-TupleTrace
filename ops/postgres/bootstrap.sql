\set ON_ERROR_STOP on
\getenv migration_password TT_MIGRATION_PASSWORD
\getenv app_password TT_APP_PASSWORD
BEGIN;
SET LOCAL password_encryption = 'scram-sha-256';
CREATE ROLE tt_migrator LOGIN PASSWORD :'migration_password';
CREATE ROLE tt_app LOGIN PASSWORD :'app_password';
CREATE ROLE tt_audit_reader NOLOGIN;

REVOKE ALL ON DATABASE :"DBNAME" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"DBNAME" TO tt_migrator, tt_app, tt_audit_reader;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE EXTENSION btree_gist WITH SCHEMA public;
CREATE SCHEMA tt AUTHORIZATION tt_migrator;
GRANT USAGE ON SCHEMA tt TO tt_app, tt_audit_reader;
ALTER ROLE tt_app IN DATABASE :"DBNAME" SET search_path = pg_catalog, tt;
ALTER ROLE tt_audit_reader IN DATABASE :"DBNAME" SET search_path = pg_catalog, tt;
COMMIT;
