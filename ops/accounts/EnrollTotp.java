import tn.supcom.tupletrace.security.TotpFactor;

import java.sql.DriverManager;
import java.util.Arrays;
import java.util.UUID;

/** Provisioning on the trusted operator station from a separately personalized authenticator. */
class EnrollTotp {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || System.console() == null) {
            throw new IllegalArgumentException("Interactive console and account UUID required");
        }
        UUID account = UUID.fromString(args[0]);
        char[] base32 = System.console().readPassword("Authenticator seed (Base32, hidden): ");
        byte[] seed = new byte[20];
        try {
            if (base32 == null || base32.length != 32) throw new IllegalArgumentException("Expected 32 Base32 symbols");
            int bits = 0, buffer = 0, offset = 0;
            for (char symbol : base32) {
                int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(symbol);
                if (value < 0) throw new IllegalArgumentException("Invalid Base32 symbol");
                buffer = (buffer << 5) | value;
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    seed[offset++] = (byte) (buffer >>> bits);
                }
            }
            try (var operator = DriverManager.getConnection(System.getenv("TT_JDBC_URL"), "tt_migrator",
                    System.getenv("TT_MIGRATION_PASSWORD"))) {
                TotpFactor.enroll(operator, account, seed);
            }
        } finally {
            if (base32 != null) Arrays.fill(base32, '\0');
            Arrays.fill(seed, (byte) 0);
        }
        System.out.println("Pending factor registered. Confirm with password and first code over HTTPS.");
    }
}
