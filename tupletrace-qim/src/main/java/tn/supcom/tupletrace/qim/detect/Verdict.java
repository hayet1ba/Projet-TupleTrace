package tn.supcom.tupletrace.qim.detect;

/**
 * Verdict de la recherche du filigrane. Le moteur ne force jamais une conclusion : dès que les
 * données ne permettent pas de conclure, la réponse est INDETERMINE.
 */
public enum Verdict {
    /** p-valeur sous le seuil : la marque du propriétaire est reconnue. */
    POSITIF("Marque reconnue"),
    /** Assez de bits relus, mais pas plus de concordances que le hasard n'en donnerait. */
    NEGATIF("Aucune marque détectée"),
    /** Les données ne permettent pas de conclure (voir la raison). */
    INDETERMINE("Indéterminé");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
