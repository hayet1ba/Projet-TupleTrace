package tn.supcom.tupletrace.qim;

import tn.supcom.tupletrace.qim.detect.DetectionResult;
import tn.supcom.tupletrace.qim.detect.WatermarkDetector;
import tn.supcom.tupletrace.qim.engine.MarkingEngine;
import tn.supcom.tupletrace.qim.engine.MarkingKey;
import tn.supcom.tupletrace.qim.engine.MarkingParameters;
import tn.supcom.tupletrace.qim.engine.MarkingRefusedException;
import tn.supcom.tupletrace.qim.engine.QualityReport;
import tn.supcom.tupletrace.qim.export.CsvWriter;
import tn.supcom.tupletrace.qim.ingest.CsvFormat;
import tn.supcom.tupletrace.qim.ingest.CsvLimits;
import tn.supcom.tupletrace.qim.ingest.CsvStreamReader;
import tn.supcom.tupletrace.qim.ingest.ValidatedBatch;
import tn.supcom.tupletrace.qim.schema.DatasetSchema;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Point d'entrée du moteur : importer un CSV, le protéger par filigrane, produire la copie
 * marquée, puis rechercher la marque dans un extrait suspect. Java pur : aucune base, aucun
 * réseau, aucune API REST ; les clés sont fournies par l'appelant.
 *
 * <h2>Deux passes : échec fermé</h2>
 * <ol>
 *   <li><b>Passe 1</b> — lecture complète, validation (pk non nulles et uniques sur tout le jeu,
 *       types, bornes) et marquage « à blanc » : rien n'est écrit. Un fichier invalide ou un
 *       marquage impossible (précision, tolérance) s'arrête ici, <b>sans aucun octet de copie</b>.</li>
 *   <li><b>Passe 2</b> — même lecture et même marquage, déterministes, cette fois écrits.</li>
 * </ol>
 * Le SHA-256 des octets lus est comparé entre les deux passes : si le fichier a changé entre-temps,
 * {@link MarkingRefusedException.Code#SOURCE_CHANGED} est levée. Dans ce cas seulement, des octets
 * ont pu être écrits : comme pour toute exception de {@code protect}, l'appelant doit jeter la sortie.
 */
public final class TupleTraceEngine {
    /** Source qui peut être ouverte deux fois (un fichier, typiquement). */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    /**
     * @param quality      rapport de qualité du marquage
     * @param sourceSha256 SHA-256 des octets du fichier source lu
     */
    public record Protection(QualityReport quality, String sourceSha256) {}

    private final DatasetSchema schema;
    private final CsvFormat format;
    private final CsvLimits limits;
    private final MarkingParameters parameters;

    public TupleTraceEngine(DatasetSchema schema, CsvFormat format, CsvLimits limits, MarkingParameters parameters) {
        this.schema = Objects.requireNonNull(schema, "schéma");
        this.format = Objects.requireNonNull(format, "format");
        this.limits = Objects.requireNonNull(limits, "limites");
        this.parameters = Objects.requireNonNull(parameters, "paramètres");
    }

    public Protection protect(Path source, MarkingKey key, OutputStream copy) throws IOException {
        Objects.requireNonNull(source, "source");
        return protect(() -> Files.newInputStream(source), key, copy);
    }

    public Protection protect(Source source, MarkingKey key, OutputStream copy) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(key, "clé");
        Objects.requireNonNull(copy, "sortie");

        // Passe 1 : tout valider et tout marquer à blanc, sans rien écrire.
        var dryRun = new MarkingEngine(key, schema, parameters);
        var firstDigest = readAll(source, dryRun::protect);

        // Passe 2 : le même calcul, écrit. Il ne peut plus échouer sur le contenu, sauf si la source a changé.
        var engine = new MarkingEngine(key, schema, parameters);
        var writer = new CsvWriter(copy, schema, format);
        String secondDigest;
        try {
            secondDigest = readAll(source, batch -> {
                try {
                    writer.writeAll(engine.protect(batch).tuples());
                } catch (IOException writeFailure) {
                    throw new UncheckedIOException(writeFailure);
                }
            });
        } catch (UncheckedIOException writeFailure) {
            throw writeFailure.getCause();
        }
        writer.flush();
        if (!firstDigest.equals(secondDigest)) {
            throw new MarkingRefusedException(MarkingRefusedException.Code.SOURCE_CHANGED, 0);
        }
        return new Protection(engine.report(), secondDigest);
    }

    /** Recherche le filigrane de cette clé dans un extrait suspect, au même format. */
    public DetectionResult detect(InputStream suspect, MarkingKey key) throws IOException {
        return new WatermarkDetector(key, schema, parameters).detect(suspect, format, limits);
    }

    private String readAll(Source source, Consumer<ValidatedBatch> sink) throws IOException {
        try (var raw = source.open(); var digesting = new DigestInputStream(raw, sha256())) {
            new CsvStreamReader(schema, format, limits).read(digesting, sink);
            digesting.transferTo(OutputStream.nullOutputStream());   // aucun octet ne doit échapper au hachage
            return HexFormat.of().formatHex(digesting.getMessageDigest().digest());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 indisponible", unavailable);
        }
    }
}
