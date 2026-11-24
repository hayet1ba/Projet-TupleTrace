package tn.supcom.tupletrace.qim.engine;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/** Vecteurs de test officiels de la RFC 5869, annexe A (cas 1 à 3, SHA-256). */
class HkdfTest {
    private static final HexFormat HEX = HexFormat.of();

    private static byte[] range(int from, int toInclusive) {
        var bytes = new byte[toInclusive - from + 1];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (from + i);
        }
        return bytes;
    }

    @Test
    void rfc5869TestCase1BasicCase() {
        var ikm = HEX.parseHex("0b".repeat(22));
        var salt = HEX.parseHex("000102030405060708090a0b0c");
        var info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
                HEX.formatHex(Hkdf.extract(salt, ikm)));
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(Hkdf.derive(ikm, salt, info, 42)));
    }

    @Test
    void rfc5869TestCase2LongerInputsAndOutputs() {
        var ikm = range(0x00, 0x4f);
        var salt = range(0x60, 0xaf);
        var info = range(0xb0, 0xff);
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
                HEX.formatHex(Hkdf.extract(salt, ikm)));
        assertEquals("b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c"
                        + "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71"
                        + "cc30c58179ec3e87c14c01d5c1f3434f1d87",
                HEX.formatHex(Hkdf.derive(ikm, salt, info, 82)));
    }

    @Test
    void rfc5869TestCase3EmptySaltAndInfo() {
        var ikm = HEX.parseHex("0b".repeat(22));
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
                HEX.formatHex(Hkdf.extract(new byte[0], ikm)));
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                HEX.formatHex(Hkdf.derive(ikm, new byte[0], new byte[0], 42)));
        assertArrayEquals(Hkdf.derive(ikm, new byte[0], new byte[0], 42), Hkdf.derive(ikm, null, null, 42));
    }

    @Test
    void refusesOutputLengthsOutsideTheRfcBounds() {
        var ikm = HEX.parseHex("0b".repeat(22));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.derive(ikm, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.derive(ikm, null, null, 255 * 32 + 1));
        assertEquals(255 * 32, Hkdf.derive(ikm, null, null, 255 * 32).length);
        assertThrows(NullPointerException.class, () -> Hkdf.derive(null, null, null, 32));
    }
}
