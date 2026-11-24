package tn.supcom.tupletrace.qim.canonical;

import tn.supcom.tupletrace.qim.canonical.CanonicalException.Code;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.CategoryValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DateValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DecimalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.NullValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.TextValue;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Encodage type–longueur–valeur de l'identité d'un tuple et d'une cellule (cahier conceptuel §2.2).
 *
 * <pre>
 * champ        = type (1 octet) || longueur (4 octets, big-endian) || valeur
 * ser_tuple    = "TTID" || version_format (1 octet)
 *                || champ(UUID, id_jeu) || champ(INT32, version_schema)
 *                || champ(pk_1) || ... || champ(pk_n)      -- colonnes CLE triées par attribute_id
 * ser_cellule  = ser_tuple || champ(INT32, attribute_id)
 * </pre>
 *
 * Aucun séparateur n'est utilisé : la longueur explicite rend l'encodage non ambigu, donc
 * (12, 34) et (1, 234) ne peuvent pas produire les mêmes octets. Ni la position d'une ligne
 * ni celle d'une colonne n'entrent dans le calcul.
 */
public final class CanonicalSerializer {
    /** Version du format : toute modification de l'encodage doit l'incrémenter. */
    public static final byte FORMAT_VERSION = 1;

    private static final byte[] MAGIC = {'T', 'T', 'I', 'D'};

    static final byte TAG_NULL = 0x00;
    static final byte TAG_INT32 = 0x01;
    static final byte TAG_INTEGER = 0x02;
    static final byte TAG_DECIMAL = 0x03;
    static final byte TAG_TEXT = 0x04;
    static final byte TAG_DATE = 0x05;
    static final byte TAG_CATEGORY = 0x06;
    static final byte TAG_UUID = 0x07;

    private CanonicalSerializer() {}

    /** Identité d'un tuple : (id_jeu, version_schéma, pk). */
    public static byte[] serializeTuple(DatasetSchema schema, CanonicalTuple tuple) {
        var out = new ByteArrayOutputStream(64);
        writeTuple(out, schema, tuple);
        return out.toByteArray();
    }

    /** Identité d'une cellule : (id_jeu, version_schéma, pk, attribute_id). */
    public static byte[] serializeCell(DatasetSchema schema, CanonicalTuple tuple, int attributeId) {
        if (schema == null) {
            throw new CanonicalException(Code.MISSING_FIELD, "schéma");
        }
        if (schema.attribute(attributeId).isEmpty()) {
            throw new CanonicalException(Code.UNKNOWN_ATTRIBUTE, "attribut " + attributeId);
        }
        var out = new ByteArrayOutputStream(72);
        writeTuple(out, schema, tuple);
        writeField(out, TAG_INT32, int32(attributeId));
        return out.toByteArray();
    }

    /** Encodage d'une valeur seule sous forme de champ type–longueur–valeur. */
    public static byte[] encodeField(CanonicalValue value) {
        if (value == null) {
            throw new CanonicalException(Code.MISSING_FIELD, "valeur");
        }
        var out = new ByteArrayOutputStream(16);
        writeValue(out, value);
        return out.toByteArray();
    }

    private static void writeTuple(ByteArrayOutputStream out, DatasetSchema schema, CanonicalTuple tuple) {
        if (schema == null || tuple == null) {
            throw new CanonicalException(Code.MISSING_FIELD, "schéma ou tuple");
        }
        out.writeBytes(MAGIC);
        out.write(FORMAT_VERSION);
        writeField(out, TAG_UUID, uuid(schema.datasetId()));
        writeField(out, TAG_INT32, int32(schema.version()));
        for (var key : schema.keyAttributes()) {
            var value = tuple.value(key.attributeId()).orElseThrow(
                    () -> new CanonicalException(Code.MISSING_VALUE, "clé " + key.attributeId()));
            if (value instanceof NullValue) {
                throw new CanonicalException(Code.NULL_KEY, "clé " + key.attributeId());
            }
            if (!CanonicalValue.fits(key.type(), value)) {
                throw new CanonicalException(Code.TYPE_MISMATCH, "clé " + key.attributeId());
            }
            writeValue(out, value);
        }
    }

    private static void writeValue(ByteArrayOutputStream out, CanonicalValue value) {
        switch (value) {
            case NullValue _ -> writeField(out, TAG_NULL, new byte[0]);
            case IntegerValue integer -> writeField(out, TAG_INTEGER, int64(integer.value()));
            case DecimalValue decimal -> writeField(out, TAG_DECIMAL,
                    ByteBuffer.allocate(12).putInt(decimal.scale()).putLong(decimal.unscaled()).array());
            case TextValue text -> writeField(out, TAG_TEXT, text.value().getBytes(StandardCharsets.UTF_8));
            case DateValue date -> writeField(out, TAG_DATE, int64(date.epochDay()));
            case CategoryValue category -> writeField(out, TAG_CATEGORY,
                    category.code().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void writeField(ByteArrayOutputStream out, byte tag, byte[] value) {
        out.write(tag);
        out.writeBytes(int32(value.length));
        out.writeBytes(value);
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] int64(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static byte[] uuid(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }
}
