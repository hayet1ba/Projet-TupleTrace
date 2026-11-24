package tn.supcom.tupletrace.qim.engine;

/**
 * Usage d'une sous-clé. Chaque décision du moteur a sa propre sous-clé HKDF : connaître le
 * résultat d'une décision (par exemple les lignes sélectionnées) n'apprend rien sur une autre
 * (par exemple les bits du filigrane).
 */
public enum KeyUsage {
    /** Une ligne est-elle marquée ? */
    SELECTION("sel"),
    /** Couche d'une ligne sélectionnée : filigrane, sceau ou empreinte. */
    LAYER("layer"),
    /** Colonne qui porte le bit. */
    COLUMN("col"),
    /** Bit du filigrane, identique sur toutes les copies. */
    WATERMARK("wm");

    private final String label;

    KeyUsage(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
