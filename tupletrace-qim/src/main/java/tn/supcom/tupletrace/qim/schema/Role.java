package tn.supcom.tupletrace.qim.schema;

/** Rôle d'un attribut, fixé côté serveur (cahier conceptuel §2.3). */
public enum Role {
    /** Colonne de la clé stable : jamais modifiée, sert à l'identité et à la sélection HMAC. */
    CLE,
    /** Identifiant, catégorie, date, code, montant exact : jamais modifié. */
    INTERDIT,
    /** INTEGER ou DECIMAL avec tolérance T > 0 et pas δ ≤ T : peut porter un bit. */
    ELIGIBLE
}
