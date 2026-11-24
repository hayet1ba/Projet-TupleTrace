package tn.supcom.tupletrace.qim.ingest;

import tn.supcom.tupletrace.qim.canonical.CanonicalTuple;
import tn.supcom.tupletrace.qim.ingest.CsvRejectedException.Code;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lecture d'un CSV en flux, par lots validés. Le fichier n'est jamais chargé en entier : on garde
 * un bloc de lecture, une ligne, le lot en cours (≤ 500 tuples) et l'index des empreintes de pk.
 *
 * <p>Si une erreur survient au lot n, les lots précédents ont déjà été remis au consommateur :
 * celui-ci doit alors tout abandonner (échec fermé). Le flux n'est pas fermé par cette classe.
 */
public final class CsvStreamReader {
    private final DatasetSchema schema;
    private final CsvFormat format;
    private final CsvLimits limits;

    public CsvStreamReader(DatasetSchema schema, CsvFormat format, CsvLimits limits) {
        this.schema = Objects.requireNonNull(schema);
        this.format = Objects.requireNonNull(format);
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * Lit tout le flux et remet chaque lot validé à {@code sink}.
     *
     * @return le nombre de tuples lus
     * @throws CsvRejectedException au premier défaut rencontré
     */
    public long read(InputStream in, Consumer<ValidatedBatch> sink) throws IOException {
        Objects.requireNonNull(sink);
        var lines = new BoundedLineReader(in, limits);
        var headerLine = lines.readLine();
        if (headerLine == null) {
            throw new CsvRejectedException(Code.EMPTY_FILE, 1);
        }
        var header = CsvLineParser.parse(headerLine, format.fieldSeparator(), limits.maxColumns(), 1);
        var validator = new TupleValidator(schema, HeaderMapping.strict(header, schema), new ValueParser(format));

        var batch = new ArrayList<CanonicalTuple>(limits.batchSize());
        long count = 0;
        String line;
        while ((line = lines.readLine()) != null) {
            long lineNumber = lines.lineNumber();
            if (++count > limits.maxLines()) {
                throw new CsvRejectedException(Code.TOO_MANY_LINES, lineNumber);
            }
            if (line.isEmpty()) {
                throw new CsvRejectedException(Code.BLANK_LINE, lineNumber);
            }
            var fields = CsvLineParser.parse(line, format.fieldSeparator(), limits.maxColumns(), lineNumber);
            batch.add(validator.validate(fields, lineNumber));
            if (batch.size() == limits.batchSize()) {
                sink.accept(validator.seal(batch));
                batch = new ArrayList<>(limits.batchSize());
            }
        }
        if (!batch.isEmpty()) {
            sink.accept(validator.seal(batch));
        }
        return count;
    }
}
