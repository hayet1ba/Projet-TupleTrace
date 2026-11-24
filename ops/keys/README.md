# Clés des jetons d’accès

Les jetons de la plateforme sont des PASETO v4.public signés avec Ed25519. La connexion HTTPS des comptes utilise le service interne d’émission après vérification du premier facteur. [Contrat de connexion](../accounts/README.md)

## Configurer le serveur et l’opérateur

Appliquer les migrations avec le [guide PostgreSQL](../postgres/README.md). V4 révoque les éventuelles anciennes entrées `ACCESS_TOKEN` du lot 2, dont le protocole et l’algorithme étaient indéterminés. Les usages de signature de preuves conservent leur propre cycle de vie.

| Variable | Processus | Valeur attendue |
| --- | --- | --- |
| `TT_KEY_DIR` | WildFly et opérateur | Chemin absolu d’un volume persistant, hors du dépôt, contenant les magasins PKCS#12 |
| `TT_KEYSTORE_PASSWORD` | WildFly et opérateur | Secret stable de forte entropie fourni par le gestionnaire de secrets du déploiement |
| `TT_TOKEN_ISSUER`, `TT_TOKEN_AUDIENCE` | WildFly | Identifiants stables de l’émetteur et de l’API, propres à l’environnement |
| `TT_JDBC_URL`, `TT_APP_PASSWORD` | WildFly | Paramètres de la datasource `java:/jdbc/TupleTraceDS` du lot 2 |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `TT_MIGRATION_PASSWORD` | Opérateur | Connexion libpq et secret du compte administratif `tt_migrator` |

L’opérateur utilise Python 3, `psql` et le `keytool` du JDK 25 présents dans le PATH. Sous Unix, créer le répertoire avec le mode 0700. Le script crée les magasins sous un umask 0077. Sous Windows, restreindre les ACL du répertoire à l’identité de service et aux administrateurs habilités avant l’initialisation. Pour plusieurs instances, monter le même volume privé sur tous les nœuds, en lecture seule côté WildFly. Le serveur et l’opérateur doivent pouvoir lire sous l’identité prévue par les droits du volume. Seul l’opérateur a besoin de son accès en écriture et du compte de migration.

## Initialiser, renouveler, révoquer

Observer le `key_id` actif par une lecture de `tt.signing_key_version` avec `purpose = 'ACCESS_TOKEN' AND status = 'ACTIVE'`. Après injection des variables, lancer l’une des commandes suivantes depuis la racine. La rotation écrit le nouveau `kid` sur sa sortie standard.

```bash
# Première activation, ou reprise après révocation de la seule clé active
python3 ops/keys/manage.py rotate

# Renouvellement à partir de la version active observée
python3 ops/keys/manage.py rotate --expected "$ACTIVE_KID"

# Retrait immédiat d’une version active ou encore acceptée en vérification
python3 ops/keys/manage.py revoke "$KID_TO_REVOKE"
```

Chaque version a un UUID et un fichier PKCS#12 chiffré contenant une entrée `PrivateKeyEntry` Ed25519. L’opérateur crée et synchronise le fichier avant d’activer sa référence en base. Une transaction PostgreSQL verrouille le périmètre plateforme et vérifie le `kid` actif attendu. Deux rotations concurrentes à partir de la même version ont un seul gagnant. La clé précédente passe en `VERIFY_ONLY` pour 15 minutes. Une révocation la rend immédiatement inutilisable pour les vérifications internes qui commencent après le commit. Si l’unique clé active est révoquée, l’émission reprend après l’activation d’une nouvelle clé.

La table conserve l’alias, le statut, la version et les dates. Le service vérifie le statut en base à chaque opération et ouvre le magasin référencé sur le volume persistant. Sauvegarder ensemble la base et le volume, et conserver leur secret de déchiffrement dans un coffre distinct. Après un échec de rotation, comparer les alias des fichiers à la base avant de retirer un fichier orphelin, car un client peut perdre la réponse d’un commit réussi. La destruction des anciennes clés privées relève de l’opérateur et de la politique de rétention.

## Vérifier un jeton et lire le JWKS

Le footer authentifié contient le `kid`. Il sélectionne une clé autorisée du périmètre plateforme. Ed25519 vérifie ensuite la signature sur l’encodage PAE de l’en-tête, du message, du footer et de l’assertion implicite vide. Le service exige `iss`, `aud`, `sub`, `jti`, `iat`, `nbf` et `exp`. Le sujet et l’identifiant du jeton sont des UUID. La durée maximale est de 15 minutes et l’expiration est exclusive. Synchroniser les horloges de la base et de chaque instance. Le contenu d’un jeton signé est lisible par son porteur.

`GET /api/.well-known/jwks.json` distribue par HTTPS les valeurs publiques `kid`, `kty=OKP`, `crv=Ed25519` et `x` pour les seules clés encore utilisables. Un consommateur doit appliquer le protocole PASETO v4.public et les mêmes règles de claims. Une copie hors ligne des clés ne peut connaître une révocation ultérieure. Le serveur répond avec `Cache-Control: no-store`.

## Dépendances et tests

Le module de sécurité utilise les primitives de format et de PAE de paseto4j-commons 2026.1. L’implémentation v4 complète de cette version choisit explicitement le fournisseur BC. TupleTrace confie la signature au service JCA Ed25519 du JDK 25 pour fonctionner dans WildFly sans inscription globale d’un fournisseur. Les trois vecteurs publics officiels 4-S-1 à 4-S-3 contrôlent l’interopérabilité, y compris une assertion implicite non vide au niveau protocole. Les données publiques utilisées sont accompagnées de leur licence ISC. [paseto4j](https://github.com/nbaars/paseto4j) · [Spécification v4](https://github.com/paseto-standard/paseto-spec/blob/master/docs/01-Protocol-Versions/Version4.md) · [Vecteurs officiels](https://github.com/paseto-standard/test-vectors) · [JWK pour Ed25519](https://www.rfc-editor.org/rfc/rfc8037.html) · [Magasins de clés Elytron](https://docs.wildfly.org/42/WildFly_Elytron_Security.html)

Sur la base de test vierge préparée selon le guide PostgreSQL, exécuter `sh ./mvnw -B -ntp -Ppostgres-it clean verify` (sous Windows, `.\mvnw.cmd -B -ntp -Ppostgres-it clean verify`). La suite lance des processus de rotation concurrents sur de vraies clés. Le job `build` déploie ensuite le WAR et une sonde de test distincte. Elle émet un jeton avant l’arrêt du serveur et vérifie sa signature dans une seconde JVM WildFly utilisant le même volume et la même base. Le WAR livré contient uniquement le code applicatif.
