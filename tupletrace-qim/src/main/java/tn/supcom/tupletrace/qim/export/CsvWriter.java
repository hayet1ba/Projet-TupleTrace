package tn.supcom.tupletrace.qim.export;

import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.CategoryValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DateValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.DecimalValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.IntegerValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.NullValue;
import tn.supcom.tupletrace.qim.canonical.CanonicalValue.TextValue;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.schema.Attribute;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Écrit des tuples canoniques en CSV UTF-8 (sans BOM, fin de ligne LF), avec le format déclaré.
 * Les colonnes sont écrites dans l'ordre des attributeId ; une valeur absente donne un champ vide
 * et une chaîne vide donne {@code ""}, pour que la relecture redonne exactement les mêmes tuples.
 *
 * <p>La sortie est mise en tampon : appeler {@link #flush()} à la fin. Le flux sous-jacent
 * n'est pas fermé par cette classe.
 */
public final class CsvWriter {
    private final Writer out;
    private final DatasetSchema schema;
    private final CsvFormat format;
    private final List<Attribute> columns;
    private boolean headerWritten;

    @SuppressWarnings("resource") // le flux appartient à l'appelant : on ne le ferme pas, on le vide (flush)
    public CsvWriter(OutputStream out, DatasetSchema schema, CsvFormat format) {
        this.out = new BufferedWriter(new OutputStreamWriter(Objects.requireNonNull(out), StandardCharsets.UTF_8));
        this.schema = Objects.requireNonNull(schema);
        this.format = Objects.requireNonNull(format);
        this.columns = schema.attributes();
    }

    public void write(CanonicalTuple tuple) throws IOException {
        Objects.requireNonNull(tuple);
        writeHeaderOnce();
        var line = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                line.append(format.fieldSeparator());
            }
            var value = tuple.value(columns.get(i).attributeId()).orElse(CanonicalValue.nullValue());
            line.append(field(value));
        }
        out.write(line.append('\n').toString());
    }

    public void writeAll(Iterable<CanonicalTuple> tuples) throws IOException {
        for (var tuple : tuples) {
            write(tuple);
        }
    }

    /** Écrit l'en-tête s'il ne l'a pas encore été (jeu vide compris), puis vide le tampon. */
    public void flush() throws IOException {
        writeHeaderOnce();
        out.flush();
    }

    public DatasetSchema schema() {
        return schema;
    }

    private void writeHeaderOnce() throws IOException {
        if (headerWritten) {
            return;
        }
        var header = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                header.append(format.fieldSeparator());
            }
            header.append(text(columns.get(i).name()));
        }
        out.write(header.append('\n').toString());
        headerWritten = true;
    }

    private String field(CanonicalValue value) {
        return switch (value) {
            case NullValue _ -> "";
            case IntegerValue integer -> Long.toString(integer.value());
            case DecimalValue decimal -> decimal.toBigDecimal().toPlainString()
                    .replace('.', format.decimalSeparator());
            case DateValue date -> LocalDate.ofEpochDay(date.epochDay()).toString();
            case TextValue text -> text(text.value());
            case CategoryValue category -> text(category.code());
        };
    }

    /** Guillemets si nécessaire ; une chaîne vide est toujours guillemetée pour la distinguer de l'absence. */
    private String text(String value) {
        if (value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("un champ ne peut pas contenir de saut de ligne");
        }
        boolean quote = value.isEmpty()
                || value.indexOf(format.fieldSeparator()) >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0;
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}
