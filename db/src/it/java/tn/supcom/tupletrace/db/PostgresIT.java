package tn.supcom.tupletrace.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import tn.supcom.tupletrace.security.PasswordAccounts;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresIT {
    private final String url = required("TT_JDBC_URL");
    private final String migrationPassword = required("TT_MIGRATION_PASSWORD");
    private final String appPassword = required("TT_APP_PASSWORD");
    private Flyway flyway;
    private int initialMigrations;

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variable requise : " + name);
        }
        return value;
    }

    private Connection migrator() throws SQLException {
        return DriverManager.getConnection(url, "tt_migrator", migrationPassword);
    }

    private Connection application() throws SQLException {
        return DriverManager.getConnection(url, "tt_app", appPassword);
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private static String scalar(Connection connection, String sql, Object... values) throws SQLException {
        try (var query = connection.prepareStatement(sql)) {
            bind(query, values);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next(), sql);
                return rows.getString(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.execute();
        }
    }

    private static void expectSqlState(Connection connection, String state, String sql, Object... values) throws SQLException {
        var savepoint = connection.setSavepoint();
        var failure = assertThrows(SQLException.class, () -> execute(connection, sql, values));
        connection.rollback(savepoint);
        assertEquals(state, failure.getSQLState(), sql);
    }

    @BeforeAll
    void migrateDisposableDatabase() throws SQLException {
        try (var db = migrator()) {
            assertEquals("tupletrace_test", scalar(db, "SELECT current_database()"));
            assertEquals(17, db.getMetaData().getDatabaseMajorVersion());
            assertEquals("0", scalar(db, "SELECT count(*) FROM pg_tables WHERE schemaname = 'tt'"),
                    "Le test exige un schéma neuf et ne nettoie jamais une base existante.");
        }
        flyway = Flyway.configure().dataSource(url, "tt_migrator", migrationPassword)
                .defaultSchema("tt").createSchemas(false).cleanDisabled(true).load();
        initialMigrations = flyway.migrate().migrationsExecuted;
    }

    @Test
    void migratesEmptySchemaThenRevalidatesWithoutChanges() {
        assertEquals(8, initialMigrations);
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(8, flyway.info().applied().length);
    }

    @Test
    void roleAssignmentsRespectOrganizationAndAdministratorSeparation() throws SQLException {
        try (var db = migrator()) {
            db.setAutoCommit(false);
            var owner = scalar(db, "INSERT INTO tt.organization(name) VALUES ('A') RETURNING id");
            var other = scalar(db, "INSERT INTO tt.organization(name) VALUES ('B') RETURNING id");
            var user = scalar(db, "INSERT INTO tt.app_user(organization_id,email) VALUES (?::uuid, ?) RETURNING id",
                    owner, "test@example.invalid");
            String grant = "INSERT INTO tt.role_assignment(organization_id,user_id,role_name) VALUES (?::uuid,?::uuid,?)";
            expectSqlState(db, "23503", grant, other, user, "AUDITEUR");
            execute(db, grant, owner, user, "DESTINATAIRE");
            execute(db, grant, owner, user, "ENQUETEUR");
            expectSqlState(db, "23P01", grant, owner, user, "ADMINISTRATEUR");
            db.rollback();
        }
    }

    @Test
    void applicationHasOnlyRuntimePrivileges() throws SQLException {
        try (var db = application()) {
            db.setAutoCommit(false);
            assertEquals("tt_app", scalar(db, "SELECT current_user"));
            for (String table : new String[]{"organization", "app_user", "role_assignment", "signing_key_version", "revoked_access_token"}) {
                scalar(db, "SELECT count(*) FROM tt." + table);
                expectSqlState(db, "42501", "DELETE FROM tt." + table);
                expectSqlState(db, "42501", "INSERT INTO tt." + table + " DEFAULT VALUES");
            }
            for (String sql : new String[]{
                    "UPDATE tt.role_assignment SET role_name='AUDITEUR'", "TRUNCATE tt.role_assignment",
                    "SELECT * FROM tt.totp_factor", "UPDATE tt.totp_factor SET last_step=NULL",
                    "DELETE FROM tt.totp_status", "INSERT INTO tt.totp_factor DEFAULT VALUES",
                    "SELECT * FROM tt.password_credential", "INSERT INTO tt.password_credential DEFAULT VALUES",
                    "UPDATE tt.password_credential SET verifier=salt", "DELETE FROM tt.login_failure",
                    "CREATE SCHEMA unexpected", "CREATE TABLE tt.unexpected(id int)",
                    "CREATE TABLE public.unexpected(id int)", "SET ROLE tt_migrator",
                    "SET ROLE tt_audit_reader", "UPDATE tt.organization SET status = 'ACTIVE'",
                    "SELECT tt.rotate_access_key(NULL,gen_random_uuid())",
                    "SELECT tt.revoke_access_key(gen_random_uuid())",
                    "UPDATE tt.signing_key_version SET status='REVOKED'",
                    "SELECT * FROM tt.flyway_schema_history", "DELETE FROM tt.flyway_schema_history",
                    "DELETE FROM tt.audit_event",
                    "SELECT * FROM tt.audit_event", "INSERT INTO tt.audit_event DEFAULT VALUES",
                    "UPDATE tt.audit_event SET event_type = 'OTHER'", "TRUNCATE tt.audit_event"}) {
                expectSqlState(db, "42501", sql);
            }
            db.rollback();
        }
    }

    @Test
    void passwordChecksUseDatabasePolicyAndAudit() throws Exception {
        var runtime = new PGSimpleDataSource();
        runtime.setURL(url);
        runtime.setUser("tt_app");
        runtime.setPassword(appPassword);
        try (var admin = migrator()) {
            PasswordScenario.verify(new PasswordAccounts(runtime), admin);
        }
    }

    @Test
    void accessKeyLifecycle(@TempDir Path store) throws Exception {
        try (var admin = migrator()) {
            AccessKeyScenario.check(url, appPassword, store, admin);
        }
    }

    @Test
    void platformHasAtMostOneActiveSigningKey() throws SQLException {
        try (var db = migrator()) {
            db.setAutoCommit(false);
            String insert = "INSERT INTO tt.signing_key_version(purpose,version,store_alias,status) " +
                    "VALUES ('ACCESS_TOKEN',?,?,'ACTIVE')";
            execute(db, insert, 1, "access-v1");
            expectSqlState(db, "23505", insert, 2, "access-v2");
            execute(db, "UPDATE tt.signing_key_version SET status='REVOKED' WHERE purpose='ACCESS_TOKEN'");
            execute(db, insert, 2, "access-v2");
            db.rollback();
        }
    }

    @Test
    void auditReaderCanReadAndJournalRejectsRewrites() throws SQLException {
        try (var db = migrator()) {
            db.setAutoCommit(false);
            assertEquals("t", scalar(db, "SELECT has_table_privilege('tt_audit_reader','tt.audit_event','SELECT')"));
            assertEquals("f", scalar(db, "SELECT has_table_privilege('tt_audit_reader','tt.audit_event','INSERT,UPDATE,DELETE,TRUNCATE')"));
            assertEquals("f", scalar(db, "SELECT has_function_privilege('tt_audit_reader',"
                    + "'tt.append_audit_event(uuid,uuid,text,uuid,uuid)','EXECUTE')"));
            expectSqlState(db, "55000", "UPDATE tt.audit_event SET target_id=NULL");
            expectSqlState(db, "55000", "DELETE FROM tt.audit_event");
            expectSqlState(db, "55000", "TRUNCATE tt.audit_event");
            db.rollback();
        }
    }

    @Test
    void auditPreservesTheActorsOrganizationAtTheTimeOfTheEvent() throws SQLException {
        try (var db = migrator()) {
            db.setAutoCommit(false);
            var original = scalar(db, "INSERT INTO tt.organization(name) VALUES ('A') RETURNING id");
            var other = scalar(db, "INSERT INTO tt.organization(name) VALUES ('B') RETURNING id");
            var actor = scalar(db, "INSERT INTO tt.app_user(organization_id,email) VALUES (?::uuid,'audit@example.invalid') RETURNING id", original);
            String append = "SELECT tt.append_audit_event(?::uuid,?::uuid,'IAM_LOGOUT',gen_random_uuid(),NULL)";
            expectSqlState(db, "23503", append, other, actor);
            execute(db, append, original, actor);
            execute(db, "UPDATE tt.app_user SET organization_id=?::uuid WHERE id=?::uuid", other, actor);
            expectSqlState(db, "23503", append, original, actor);
            assertEquals(original, scalar(db, "SELECT organization_id FROM tt.audit_event WHERE actor_id=?::uuid", actor));
            db.rollback();
        }
    }

    @Test
    void runtimeAppendsTwoLinkedAuditEvents() throws SQLException {
        UUID correlation = UUID.randomUUID();
        try (var db = application();
             var append = db.prepareStatement("SELECT tt.append_audit_event(NULL,NULL,?, ?,NULL)")) {
            db.setAutoCommit(false);
            for (String event : new String[]{"STARTED", "COMPLETED"}) {
                append.setString(1, event);
                append.setObject(2, correlation);
                append.execute();
            }
            db.commit();
        }
        try (var db = migrator(); var read = db.prepareStatement(
                "SELECT previous_hash,event_hash FROM tt.audit_event WHERE correlation_id=? ORDER BY sequence_no")) {
            read.setObject(1, correlation);
            try (var rows = read.executeQuery()) {
                assertTrue(rows.next());
                assertArrayEquals(new byte[32], rows.getBytes("previous_hash"));
                byte[] firstHash = rows.getBytes("event_hash");
                assertTrue(rows.next());
                assertArrayEquals(firstHash, rows.getBytes("previous_hash"));
                assertFalse(java.util.Arrays.equals(firstHash, rows.getBytes("event_hash")));
                assertFalse(rows.next());
            }
        }
    }
}
