package tn.supcom.tupletrace.qim.schema;

import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class Rejections {
    private Rejections() {}

    static void assertRejected(SchemaException.Code expected, Executable construction) {
        var refused = assertThrows(SchemaException.class, construction);
        assertEquals(expected, refused.code());
    }
}
