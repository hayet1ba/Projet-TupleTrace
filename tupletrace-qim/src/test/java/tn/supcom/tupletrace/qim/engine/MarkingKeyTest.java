package tn.supcom.tupletrace.qim.engine;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarkingKeyTest {
    private static final UUID DATASET = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_DATASET = UUID.fromString("00000000-0000-4000-8000-000000000002");

    private static byte[] randomBytes() {
        var bytes = new byte[MarkingKey.LENGTH];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    @Test
    void sameKeyAndContextAlwaysGiveTheSameSubkey() {
        var bytes = randomBytes();
        try (var first = MarkingKey.of(bytes); var second = MarkingKey.of(bytes)) {
            assertArrayEquals(first.subkey(KeyUsage.SELECTION, DATASET, 1),
                    second.subkey(KeyUsage.SELECTION, DATASET, 1));
        }
    }

    @Test
    void everyUsageDatasetAndVersionGetsItsOwnSubkey() {
        try (var key = MarkingKey.generate()) {
            var subkeys = new HashSet<String>();
            for (var usage : EnumSet.allOf(KeyUsage.class)) {
                subkeys.add(HexFormat.of().formatHex(key.subkey(usage, DATASET, 1)));
                subkeys.add(HexFormat.of().formatHex(key.subkey(usage, DATASET, 2)));
                subkeys.add(HexFormat.of().formatHex(key.subkey(usage, OTHER_DATASET, 1)));
            }
            assertEquals(3 * KeyUsage.values().length, subkeys.size());
        }
    }

    @Test
    void anotherMasterKeyGivesOtherSubkeys() {
        try (var first = MarkingKey.generate(); var second = MarkingKey.generate()) {
            assertFalse(Arrays.equals(first.subkey(KeyUsage.WATERMARK, DATASET, 1),
                    second.subkey(KeyUsage.WATERMARK, DATASET, 1)));
        }
    }

    @Test
    void copiesTheCallerArraySoItCanBeErased() {
        var bytes = randomBytes();
        try (var key = MarkingKey.of(bytes); var reference = MarkingKey.of(bytes)) {
            Arrays.fill(bytes, (byte) 0);
            assertArrayEquals(reference.subkey(KeyUsage.COLUMN, DATASET, 1), key.subkey(KeyUsage.COLUMN, DATASET, 1));
        }
    }

    @Test
    void refusesKeysThatAreNotTwoHundredFiftySixBits() {
        assertThrows(IllegalArgumentException.class, () -> MarkingKey.of(new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> MarkingKey.of(new byte[33]));
        assertThrows(NullPointerException.class, () -> MarkingKey.of(null));
    }

    @Test
    void neverPrintsTheKeyAndCannotBeUsedOnceErased() {
        var bytes = randomBytes();
        var key = MarkingKey.of(bytes);
        var text = key.toString();
        assertFalse(text.contains(HexFormat.of().formatHex(bytes)));
        assertFalse(text.contains(Arrays.toString(bytes)));
        assertEquals("MarkingKey[****]", text);

        key.close();
        assertThrows(IllegalStateException.class, () -> key.subkey(KeyUsage.SELECTION, DATASET, 1));
    }
}
