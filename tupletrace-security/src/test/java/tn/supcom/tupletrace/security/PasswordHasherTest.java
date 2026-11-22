package tn.supcom.tupletrace.security;

import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test
    void agreesWithOpenSslArgon2id() {
        var hex = HexFormat.of();
        var salt = hex.parseHex("000102030405060708090a0b0c0d0e0f");
        var input = "TupleTrace-interoperability".toCharArray();
        assertArrayEquals(hex.parseHex("fdedb33ae44260418678582c0bcd889d1619b68394cd270dcc5916d0b3b0999c"),
                PasswordHasher.derive(input, salt));
    }

    @Test
    void countsUnicodeCodePointsWithoutTruncation() {
        assertFalse(PasswordHasher.accepts("🔒".repeat(11).toCharArray()));
        assertTrue(PasswordHasher.accepts("🔒".repeat(12).toCharArray()));
        assertFalse(PasswordHasher.accepts(new char[1025]));
        assertFalse(PasswordHasher.accepts(("a".repeat(12) + Character.MIN_HIGH_SURROGATE).toCharArray()));
    }
}
