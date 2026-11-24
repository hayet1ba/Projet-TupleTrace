package tn.supcom.tupletrace.security;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Vérification interne du premier facteur. Les appels utilisent une connexion hors transaction JTA ambiante. */
public final class PasswordAccounts {
    public enum Status { VERIFIED, DENIED, RATE_LIMITED }
    public record Result(Status status, UUID userId) {}
    private static final Result DENIED = new Result(Status.DENIED, null);
    private static final Result LIMITED = new Result(Status.RATE_LIMITED, null);
    private static final byte[] DUMMY_SALT = PasswordHasher.newSalt();
    private static final byte[] DUMMY_HASH = new byte[PasswordHasher.HASH_BYTES];
    private static final Semaphore CAPACITY = new Semaphore(4);
    private final DataSource database;

    public PasswordAccounts(DataSource database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Connexion de provisionnement privilégiée. Un second appel ne remplace pas le premier mot de passe. */
    public static void enroll(Connection operator, UUID userId, char[] password) throws SQLException {
        if (!PasswordHasher.accepts(password)) throw new IllegalArgumentException("Invalid password length or encoding");
        byte[] salt = PasswordHasher.newSalt();
        byte[] verifier = PasswordHasher.derive(password, salt);
        try (var insert = operator.prepareStatement(
                "INSERT INTO tt.password_credential(user_id,salt,verifier) VALUES (?,?,?)")) {
            insert.setObject(1, userId);
            insert.setBytes(2, salt);
            insert.setBytes(3, verifier);
            insert.executeUpdate();
        } finally {
            Arrays.fill(verifier, (byte) 0);
        }
    }

    public Result verify(String email, char[] password, InetAddress peer, UUID correlation) throws SQLException {
        Objects.requireNonNull(correlation);
        // PostgreSQL inet attend l'adresse IPv6 sans l'identifiant d'interface local.
        String address = peer.getHostAddress().split("%", 2)[0];
        String login = email == null || email.length() > 1024 || email.indexOf('\0') >= 0 ? "" : email;
        if (!CAPACITY.tryAcquire()) return LIMITED;
        try (var connection = database.getConnection()) {
            // Les lectures après attente du verrou doivent voir les échecs déjà confirmés.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Result result = check(connection, login, password, address);
                if (result.status() == Status.VERIFIED) {
                    connection.rollback(); // Annule uniquement la réservation de cette tentative.
                } else {
                    try (var audit = connection.prepareStatement(
                            "SELECT tt.append_audit_event(NULL,NULL,'PASSWORD_CHECK_DENIED',?,NULL)")) {
                        audit.setObject(1, correlation);
                        audit.execute();
                    }
                    connection.commit();
                }
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } finally {
            CAPACITY.release();
        }
    }

    private static Result check(Connection connection, String login, char[] password, String address) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM tt.reserve_password_check(?,?::inet)")) {
            query.setString(1, login);
            query.setString(2, address);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new SQLException("Missing password check decision");
                switch (row.getString("decision")) {
                    case "ORIGIN_LIMIT": return LIMITED;
                    case "ACCOUNT_DELAY": return DENIED;
                    case "CHECK": break;
                    default: throw new SQLException("Unknown password check decision");
                }
                UUID user = row.getObject("user_id", UUID.class);
                boolean validInput = PasswordHasher.accepts(password);
                byte[] expected = user == null ? DUMMY_HASH : row.getBytes("verifier");
                try {
                    boolean match = PasswordHasher.matches(validInput ? password : new char[0],
                            user == null ? DUMMY_SALT : row.getBytes("salt"), expected);
                    return match && validInput && user != null ? new Result(Status.VERIFIED, user) : DENIED;
                } finally {
                    if (user != null) Arrays.fill(expected, (byte) 0);
                }
            }
        }
    }

}
