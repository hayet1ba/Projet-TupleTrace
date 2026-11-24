package tn.supcom.tupletrace.qim.ingest;

/**
 * Bornes de lecture (SR-DIS-02, SR-DIS-03). Une instance ne peut que resserrer les bornes du
 * cahier des charges, jamais les dépasser : {@link #DEFAULT} est le maximum autorisé.
 *
 * @param maxBytes     taille totale du fichier, en octets
 * @param maxLines     nombre de lignes de données, en-tête non compris
 * @param maxColumns   nombre de colonnes
 * @param maxLineBytes taille d'une ligne en octets, fin de ligne non comprise
 * @param batchSize    nombre de tuples par lot remis au consommateur
 */
public record CsvLimits(long maxBytes, long maxLines, int maxColumns, int maxLineBytes, int batchSize) {
    public static final long MAX_BYTES = 50L * 1024 * 1024;
    public static final long MAX_LINES = 1_000_000L;
    public static final int MAX_COLUMNS = 64;
    public static final int MAX_LINE_BYTES = 16 * 1024;
    public static final int BATCH_SIZE = 500;

    public static final CsvLimits DEFAULT =
            new CsvLimits(MAX_BYTES, MAX_LINES, MAX_COLUMNS, MAX_LINE_BYTES, BATCH_SIZE);

    public CsvLimits {
        requireWithin("maxBytes", maxBytes, MAX_BYTES);
        requireWithin("maxLines", maxLines, MAX_LINES);
        requireWithin("maxColumns", maxColumns, MAX_COLUMNS);
        requireWithin("maxLineBytes", maxLineBytes, MAX_LINE_BYTES);
        requireWithin("batchSize", batchSize, BATCH_SIZE);
    }

    private static void requireWithin(String name, long value, long maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " doit être compris entre 1 et " + maximum);
        }
    }
}
