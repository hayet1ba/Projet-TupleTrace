package tn.supcom.tupletrace.qim.ingest;

/**
 * Bilan de la lecture d'un extrait suspect.
 *
 * @param rowsRead           lignes de données lues
 * @param rowsSkipped        lignes ignorées : clé vide ou illisible, nombre de champs incorrect,
 *                           guillemets mal formés, ligne vide
 * @param duplicateKeys      pk rencontrées plus d'une fois : l'identité devient ambiguë
 * @param keyColumnsPresent  toutes les colonnes CLE figurent dans l'en-tête
 * @param ambiguousHeader    un même nom de colonne apparaît plusieurs fois dans l'en-tête
 */
public record SuspectSummary(long rowsRead, long rowsSkipped, long duplicateKeys,
                             boolean keyColumnsPresent, boolean ambiguousHeader) {}
