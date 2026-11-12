# TupleTrace

**Plateforme sécurisée de partage de jeux de données traçables : tatouage, stéganographie et empreinte numérique de données tabulaires.**

Atelier PWA/SPA du module *Sécurité Applicative* — SUP'COM (École Supérieure des Communications de Tunis), INDP3 CySeD, année universitaire 2026–2027.

| | |
|---|---|
| **Socle** | Jakarta EE 11 · WildFly 42 · Java 25 |
| **Équipe** | OMRANE Yosr · AMMAR Chaima · BEN AMOR Hayet |
| **Encadrant** | Dr. Ing. Mohamed-Becha KAÂNICHE |

---

## Le problème

Comment partager un jeu de données tabulaire avec plusieurs destinataires tout en pouvant :

1. établir **à qui il appartient** ;
2. vérifier de façon autonome **qu'il est authentique** ;
3. identifier **la copie dont provient un extrait** retrouvé, sans dégrader significativement son utilité ?

Le risque principal n'est pas le piratage, mais la **fuite après un accès légitime**.

## Les trois techniques

| Situation | Technique | Ce qu'elle établit |
|---|---|---|
| Réutilisation non autorisée | **Watermarking** : marque commune liée à la clé du propriétaire | À qui appartiennent les données |
| Jeu falsifié ou usurpé | **Stéganographie** : sceau signé et chiffré caché dans les lignes | Ce qui a été publié, par qui, dans quelle version |
| Fuite par un partenaire | **Fingerprinting** : code unique par copie | À qui la copie a été remise |

L'unité de travail est le **tuple** (une ligne identifiée par une clé stable). L'identité d'une cellule est `(id_jeu, version_schéma, pk, attribute_id)` : la position d'une ligne ou d'une colonne n'est jamais une identité.

### Méthode de marquage (résumé)

Une fraction des lignes (≈ 20 % par défaut) est sélectionnée par `HMAC-SHA-256(K, pk)`, puis répartie en trois ensembles disjoints : filigrane, sceau, empreinte. Les bits sont insérés par quantification à pas δ (QIM) dans des colonnes numériques qui tolèrent un faible écart. Les identifiants, dates, codes et catégories ne sont jamais modifiés. La détection repose sur un test binomial (seuil p < 10⁻⁶ pour le filigrane) et, pour l'attribution, sur une correction de Bonferroni.

Un marquage qui dépasserait la tolérance d'une colonne est **refusé**, et un doute se traduit par le verdict **« Indéterminé »**, jamais par une attribution forcée.

## Fonctionnalités

Le projet compte **35 besoins fonctionnels** répartis en 8 modules :

| Module | Contenu |
|---|---|
| **Socle** | Comptes sur invitation, MFA (TOTP), réinitialisation, données personnelles |
| **Jeux de données** | Import CSV, profilage, colonnes marquables et tolérances, versions et SHA-256 |
| **Watermarking** | Marque commune, rapport de qualité, refus motivé |
| **Stéganographie** | Sceau caché signé (Ed25519) et chiffré (AES-256-GCM), vérification autoportée |
| **Fingerprinting** | Destinataires, double validation, copies, téléchargement contrôlé, révocation, registre signé |
| **Investigation** | Extrait suspect, tests statistiques, verdict à 4 statuts, rapport signé, simulateur de transformations |
| **Administration** | Comptes, organisations, quotas, audit, rotation des clés |
| **PWA** | Application installable, page hors ligne |

### Verdict d'investigation

`Marque reconnue` · `Copie identifiée` · `Indéterminé` · `Aucune marque détectée`, avec niveau de confiance et réserves.

> Retrouver la copie d'un destinataire ne prouve pas que cette personne a divulgué les données. Le rapport formule un **indice technique**, jamais une accusation.

## Acteurs et rôles

Propriétaire (MFA) · Gestionnaire de distribution · Destinataire · Enquêteur · Administrateur technique (MFA) · Auditeur (MFA).

Les droits suivent le principe du **refus par défaut**. Un gestionnaire prépare, un propriétaire différent valide, et l'administrateur technique n'a accès ni aux jeux, ni aux copies, ni aux clés.

## Architecture

```
Navigateur (PWA / SPA React) ── zone non fiable
        │  F1 — HTTPS TLS 1.3 · JWT en mémoire
        ▼
WildFly 42 — Jakarta EE 11 / Java 25 ── zone de confiance serveur
   Jakarta REST /api/**  ▷ Jakarta Security + Elytron (JWT ES256, RBAC, MFA)
   Services CDI : Import · Distribution · Investigation
        │  F2 — tuples canonisés et validés + SecurityContext
        ▼
   Moteur TupleTrace : filigrane · sceau · empreinte
   (file bornée, timeout 60 s)  ▷ Credential store Elytron  ◀ F4
        │  F3 — copie chiffrée AES-256-GCM après validation
        ▼
   PostgreSQL 17 (registre, audit) · Stockage fichiers chiffré · Journal en ajout seul
```

Le marquage est appliqué après la frontière F2 et avant F3 : c'est le seul endroit où le clair des tuples existe, en mémoire contrôlée. Un serveur compromis pendant un traitement peut observer ces tuples, et TupleTrace ne prétend pas offrir un chiffrement de bout en bout.

## Pile technique

| Couche | Technologie |
|---|---|
| Langage / JVM | Java 25 |
| Serveur d'application | WildFly 42 (Elytron, Undertow) |
| Plateforme | Jakarta EE 11 (REST 4.0, CDI 4.1, Persistence 3.2, Data 1.0, Validation 3.1, Security 4.0, Concurrency 3.1) |
| Cryptographie | JCA + Bouncy Castle : HMAC-SHA-256, HKDF, AES-256-GCM, Argon2id, Ed25519, ES256 |
| Base de données | PostgreSQL 17 (clés hors base) |
| Client | React 19 + TypeScript, Vite, vite-plugin-pwa |
| Intégration continue | GitHub Actions : build, tests, SAST, SCA, recherche de secrets |

## Sécurité

La démarche suit l'agenda du cours (SQUARE complété par des cas d'abus) avec une logique Shift-Left : les exigences sont spécifiées avant le code métier.

- **62 exigences de sécurité (SR)** en 11 familles : CON, INT, NRP, AUT, AUZ, PRV, LOG, ERR, DIS, TST, DEP. Le référentiel est dans [`docs/exigences.csv`](docs/exigences.csv).
- **18 cas d'abus (CA)**, 6 profils d'attaquants, analyse STRIDE et 2 arbres d'attaque.
- **Cotation des risques** selon l'OWASP Risk Rating (R-01 à R-07).
- **Principes de conception** : séparation des rôles, preuve recalculée côté serveur, intégrité vérifiable par un autre rôle, aucune promesse excessive.

## Fiabilité du code métier (SRE)

L'approche SRE porte sur le **code de dissimulation** (marquage, extraction, attribution), pas sur la haute disponibilité de l'infrastructure. Les SLI suivis incluent la validation sûre des imports, la conformité de la distorsion, l'atomicité des distributions, le taux d'erreur d'extraction (BER), la récupération du sceau, la précision d'attribution, les faux positifs, le taux d'indétermination, les latences p95 et les erreurs de heap.

> **Cible n'est pas résultat.** Toutes les valeurs chiffrées (par exemple 100 000 lignes protégées en moins de 60 s sur 4 vCPU, ≥ 95 % d'attribution, 0 fausse attribution sur 1 000 témoins) sont des objectifs à mesurer au jalon J2 sur une machine et une JVM déclarées. Une exigence n'est déclarée « vérifiée » qu'après l'exécution d'un test dont les observations sont conservées.

## Limites assumées

- Aucun marquage tabulaire n'est infaillible contre la suppression massive de lignes, la suppression des clés stables, les agrégations destructrices ou la collusion de plusieurs destinataires.
- Un arrondi plus grossier que δ ou un bruit d'amplitude ≥ δ/2 détruit la marque : le verdict devient « Indéterminé ».
- Une fuite survenant après le téléchargement échappe au contrôle d'accès de la plateforme.
- Le prototype ne reconnaît pas un tuple dont la clé stable a été supprimée ou dont l'identité est ambiguë.

## Documentation

Les documents de référence sont dans le dossier [`docs/`](docs/) :

| Fichier | Contenu |
|---|---|
| `cahier_de_charge.pdf` | Cahier des charges : contexte, objectifs, périmètre, méthode de marquage, architecture, démarche de sécurité, planning |
| `besoins_fonctionnels_et_sre.pdf` | Besoins fonctionnels, référentiel SR, cotation OWASP, SLI/SLO, campagnes de vérification, traçabilité |
| `exigences.csv` | Référentiel des 62 SR (séparateur `;`, UTF-8 avec BOM), à régénérer à chaque évolution |

## Tests et évaluation

Le banc d'essai comprend 30 jeux synthétiques (lois uniforme, gaussienne, entiers bornés) de 1 000, 10 000 et 100 000 lignes, plus 2 jeux publics adaptés, générés par script avec une graine fixe. Un simulateur applique suppression de lignes ou de colonnes, arrondi, bruit, réordonnancement et combinaisons. Seules des **données synthétiques ou publiques** sont utilisées en test (SR-PRV-02).

Définition of Done : critères d'acceptation vérifiés par des tests, cas d'abus couverts, exigences de sécurité testées, CI verte (build, tests, SAST, SCA, secrets), revue de code et matrice de traçabilité à jour.

## Démarrage

Prérequis prévus : JDK 25, WildFly 42, PostgreSQL 17, Node.js (pour le client React/Vite).

> Les commandes de build, de configuration du credential store et de lancement seront ajoutées ici à mesure que le dépôt se construit. Le secret qui protège le credential store ne doit jamais figurer dans le dépôt, dans la base ni à côté du fichier du store : il est injecté au démarrage.

## Contribuer

- Chaque commit remonte à une issue, une exigence et un test (SR-TST-03).
- Un autre membre de l'équipe relit chaque exigence avant le commit final (checklist du recueil §10.2).
- Aucun secret, aucune clé et aucune donnée réelle dans le dépôt ; la recherche de secrets tourne en CI.

## Références

- Support du cours Sécurité Applicative
- OWASP ASVS · OWASP Risk Rating Methodology
- Documentation Jakarta EE, Jakarta Security 4.0, WildFly 42 / Elytron
- R. Agrawal et J. Kiernan, « Watermarking Relational Databases », VLDB 2002
- SQUARE (Mead et al., CMU/SEI)
