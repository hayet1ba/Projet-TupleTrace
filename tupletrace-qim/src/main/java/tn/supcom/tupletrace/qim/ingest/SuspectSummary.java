package tn.supcom.tupletrace.qim.ingest;

/**
 * Bilan de la lecture d'un extrait suspect.
 *
 * @param rowsRead            lignes de données lues
 * @param rowsSkipped         lignes ignorées : clé vide ou illisible, nombre de champs incorrect,
 *                            guillemets mal formés, ligne vide
 * @param repeatedKeys        pk distinctes qui apparaissent plusieurs fois
 * @param rowsWithRepeatedKey lignes portant une de ces pk, toutes occurrences comprises : elles sont
 *                            écartées de la décision, car on ne peut pas savoir laquelle est la vraie
 * @param keyColumnsPresent   toutes les colonnes CLE figurent dans l'en-tête
 * @param ambiguousHeader     un même nom de colonne apparaît plusieurs fois dans l'en-tête
 */
public record SuspectSummary(long rowsRead, long rowsSkipped, long repeatedKeys, long rowsWithRepeatedKey,
                             boolean keyColumnsPresent, boolean ambiguousHeader) {}
