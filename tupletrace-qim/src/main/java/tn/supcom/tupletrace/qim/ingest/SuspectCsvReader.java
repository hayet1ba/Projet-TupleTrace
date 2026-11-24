package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.canonical.CanonicalException;
import tn.supcom.tupletrace.qim.canonical.CanonicalSerializer;
import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;
import tn.supcom.tupletrace.qim.schema.Role;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lecture tolérante d'un extrait suspect pour l'investigation. Les bornes de taille restent les
 * mêmes qu'à l'import (un extrait n'est pas une porte d'entrée pour saturer le serveur), mais les
 * défauts de contenu sont comptés au lieu d'arrêter la lecture : un extrait divulgué est souvent
 * tronqué, réordonné, partiellement supprimé ou bruité.
 * <ul>
 *   <li>colonnes inconnues ou vides dans l'en-tête : ignorées ;</li>
 *   <li>colonne CLE absente : aucune ligne n'est exploitable ({@code keyColumnsPresent = false}) ;</li>
 *   <li>nom de colonne répété : en-tête ambigu, aucune ligne n'est remise ;</li>
 *   <li>pk vide ou illisible, ligne mal formée : ligne ignorée et comptée ;</li>
 *   <li>pk répétée : comptée comme doublon (l'appelant conclura à une identité ambiguë) ;</li>
 *   <li>nombre bruité (plus de décimales que le type) : accepté tel quel.</li>
 * </ul>
 */
public final class SuspectCsvReader {
    private final DatasetSchema schema;
    private final CsvFormat format;
    private final CsvLimits limits;

    public SuspectCsvReader(DatasetSchema schema, CsvFormat format, CsvLimits limits) {
        this.schema = Objects.requireNonNull(schema);
        this.format = Objects.requireNonNull(format);
        this.limits = Objects.requireNonNull(limits);
    }

    public SuspectSummary read(InputStream in, Consumer<SuspectRow> sink) throws IOException {
        Objects.requireNonNull(sink);
        var lines = new BoundedLineReader(in, limits);
        var headerLine = lines.readLine();
        if (headerLine == null) {
            return new SuspectSummary(0, 0, 0, false, false);
        }
        var header = CsvLineParser.parse(headerLine, format.fieldSeparator(), limits.maxColumns(), 1);
        var byPosition = new Attribute[header.size()];
        var seen = new HashSet<String>();
        boolean ambiguous = false;
        var attributesByName = new HashMap<String, Attribute>();
        schema.attributes().forEach(a -> attributesByName.put(key(a.name()), a));
        for (int position = 0; position < header.size(); position++) {
            var name = header.get(position);
            if (name == null || name.isBlank()) {
                continue;
            }
            ambiguous |= !seen.add(key(name));
            byPosition[position] = attributesByName.get(key(name));
        }
        boolean keysPresent = schema.keyAttributes().stream().allMatch(k -> seen.contains(key(k.name())));
        if (!keysPresent || ambiguous) {
            return new SuspectSummary(0, 0, 0, keysPresent, ambiguous);
        }

        var parser = new ValueParser(format);
        var keys = new PkUniquenessIndex();
        long read = 0;
        long skipped = 0;
        long duplicates = 0;
        String line;
        while ((line = lines.readLine()) != null) {
            long lineNumber = lines.lineNumber();
            if (++read > limits.maxLines()) {
                throw new CsvRejectedException(Code.TOO_MANY_LINES, lineNumber);
            }
            var row = parseRow(line, lineNumber, byPosition, parser);
            if (row == null) {
                skipped++;
                continue;
            }
            if (!keys.add(CanonicalSerializer.serializeTuple(schema, row.key()))) {
                duplicates++;
            }
            sink.accept(row);
        }
        return new SuspectSummary(read, skipped, duplicates, true, false);
    }

    /** Ligne exploitable, ou {@code null} si elle doit être ignorée. */
    private SuspectRow parseRow(String line, long lineNumber, Attribute[] byPosition, ValueParser parser) {
        if (line.isEmpty()) {
            return null;
        }
        List<String> fields;
        try {
            fields = CsvLineParser.parse(line, format.fieldSeparator(), limits.maxColumns(), lineNumber);
        } catch (CsvRejectedException malformed) {
            if (malformed.code() == Code.MALFORMED_QUOTES || malformed.code() == Code.UNTERMINATED_QUOTE) {
                return null;
            }
            throw malformed;
        }
        if (fields.size() != byPosition.length) {
            return null;
        }
        var keyValues = new HashMap<Integer, CanonicalValue>();
        var numbers = new HashMap<Integer, BigDecimal>();
        for (int position = 0; position < fields.size(); position++) {
            var attribute = byPosition[position];
            if (attribute == null) {
                continue;
            }
            if (attribute.role() == Role.CLE) {
                CanonicalValue value;
                try {
                    value = parser.parse(fields.get(position), attribute.type(), lineNumber);
                } catch (CsvRejectedException unreadableKey) {
                    return null;
                }
                if (value instanceof CanonicalValue.NullValue) {
                    return null;
                }
                keyValues.put(attribute.attributeId(), value);
            } else if (attribute.role() == Role.ELIGIBLE) {
                var number = parser.parseLenientNumber(fields.get(position));
                if (number != null) {
                    numbers.put(attribute.attributeId(), number);
                }
            }
        }
        try {
            var key = CanonicalTuple.of(keyValues);
            CanonicalSerializer.serializeTuple(schema, key);
            return new SuspectRow(key, numbers);
        } catch (CanonicalException unusableKey) {
            return null;
        }
    }

    private static String key(String name) {
        return HeaderMapping.key(name);
    }
}
