# Premier facteur des comptes TupleTrace

## Connexion et accès HTTPS

`POST /api/auth/login` accepte un objet JSON de 8 192 octets au plus, avec exactement deux chaînes, `email` et `password`. Les doublons et les champs supplémentaires sont refusés. Fournir les identifiants dans le corps HTTPS. L'origine utilisée par le limiteur est celle du pair réseau observé par Undertow. Éviter toute journalisation des corps de connexion et de l'en-tête Authorization dans un proxy ou une sonde.

| Résultat | HTTP |
| --- | --- |
| Premier facteur accepté, compte et organisation actifs | 200, objet JSON contenant `access_token` et `token_type=Bearer` |
| Mauvais identifiants, compte indisponible ou délai du compte | 401, même réponse |
| Limite par origine ou capacité de hachage atteinte | 429 |
| JSON incorrect, corps trop grand ou média incompatible | 400, 413 ou 415 |
| Base ou matériel de signature indisponible | 503 |

Les réponses de connexion portent `Cache-Control: no-store`. Transmettre le jeton dans `Authorization: Bearer …` sur HTTPS. Pour un client navigateur, le garder uniquement en mémoire. Le jeton signé lie le sujet à l'UUID de son organisation pour 15 minutes au plus. Les rôles sont relus dans PostgreSQL. Un transfert d'organisation exige une nouvelle connexion.

Appliquer les migrations jusqu'à V6, déployer le pilote JDBC et configurer la datasource, puis exécuter `ops/wildfly/authentication.cli` **avant** de déployer le WAR. Ce script configure les mappings Undertow et EJB du domaine dédié TupleTrace. Le mécanisme Jakarta Security vérifie le PASETO, la clé, le `jti` et l'appartenance active. Elytron résout le principal auprès du realm JDBC et fournit les rôles au conteneur. `GET /api/organizations/{uuid}` exige le rôle AUDITEUR et limite la lecture à l'organisation du compte. Un rôle insuffisant donne 403 et une autre organisation donne 404.

La révocation individuelle consiste à inscrire le `jti` vérifié et son expiration signée dans `tt.revoked_access_token` avec une identité d'exploitation autorisée. Les vérifications suivantes consultent cette table. L'opérateur peut purger les entrées après expiration. La révocation d'une clé conserve son effet sur tous ses jetons. `tt_app` a uniquement le droit SELECT sur la liste des révocations.

Pendant la transition, les anciens comptes du realm WildFly conservent BASIC sur `GET /api/private`. Ils ne peuvent ni obtenir un jeton à ce titre ni accéder aux ressources d'organisation. Le retrait de cette branche de compatibilité et du realm historique pourra suivre la migration des consommateurs et la validation en environnement cible.

## Évolution vers OAuth

La connexion ci-dessus est propre à TupleTrace. Pour Authorization Code + PKCE, le futur client React sera un client public enregistré avec ses URI de redirection exactes. Il générera un vérificateur aléatoire, conservera en mémoire le vérificateur et l'état de la demande, puis enverra un challenge S256 à un serveur d'autorisation. Celui-ci authentifiera l'utilisateur et émettra un code court à usage unique, lié au client, à la redirection et au challenge. Son endpoint de jetons vérifiera ces liens et le vérificateur PKCE avant d'appeler le service d'émission. L'API conservera son rôle de serveur de ressources avec validation de l'émetteur, de l'audience et des autorisations courantes. [RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html)

## Provisionnement et vérification

V5 conserve les identités et les affectations de rôles existantes. Un compte créé avant V5 peut rester sans vérificateur. Pour l'équiper, le code de provisionnement de confiance appelle `PasswordAccounts.enroll(connection, userId, password)` sur une connexion autorisée en écriture. Un second appel pour le même utilisateur échoue. L'appelant contrôle la transaction de provisionnement et efface son tableau `char[]` après usage.

`tt.password_credential` contient l'UUID du compte, le profil cryptographique, un sel aléatoire de 16 octets et le résultat binaire de 32 octets. Le profil fixe Argon2id v19, 65 536 Kio de mémoire, trois passes et une voie, conformément au cahier conceptuel (§6.1). Bouncy Castle 1.87 est une dépendance du WAR. La saisie est encodée en UTF-8, sans troncature ni transformation des espaces, des accents ou de la casse. Le minimum est de 12 points de code Unicode. La limite d'entrée de 1 024 unités UTF-16 borne le traitement de saisies démesurées. Le profil devra être recalibré sur le serveur cible avant production. [Bouncy Castle Java](https://www.bouncycastle.org/download/bouncy-castle-java/) · [OWASP Password Storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)

`PasswordAccounts.verify(email, password, peer, correlation)` est un service interne. Lui fournir la datasource applicative en dehors d'une transaction JTA ambiante, l'adresse réseau du pair observée par le serveur et un UUID de corrélation. La connexion JDBC réservée à l'appel gère sa transaction. Le résultat `VERIFIED` identifie exclusivement un premier facteur réussi. La vérification exige un utilisateur actif, une organisation active et un vérificateur. La base normalise le courriel comme en V1. Un compte inconnu, suspendu, fermé, invité ou sans mot de passe et une saisie incorrecte donnent tous `DENIED`, sans UUID. Les tentatives admises passent par un calcul Argon2id de même coût, y compris avec un vérificateur factice en l'absence de compte utilisable. La comparaison des 32 octets utilise `MessageDigest.isEqual`.

## Limitation et audit

PostgreSQL sérialise les contrôles concurrents d'une même adresse ou d'un même identifiant sur toutes les instances. V5 réserve provisoirement l'échec avant le calcul coûteux. Un succès annule cette réservation. Un refus la confirme avec un événement `PASSWORD_CHECK_DENIED` dans le journal existant. Celui-ci contient l'UUID de corrélation et omet courriel, adresse, mot de passe et vérificateur. Le rôle `tt_app` peut appeler la fonction de réservation et lire le candidat correspondant à une tentative admise. Les deux nouvelles tables sont réservées au propriétaire SQL.

| Périmètre | Politique initiale |
| --- | --- |
| Identifiant, connu ou inconnu | Après 5 échecs sur 15 minutes glissantes, délai de 1 seconde. Chaque nouvel échec admis double le délai, jusqu'à 5 minutes. Une tentative pendant le délai reçoit le refus générique et compte pour l'origine. |
| Adresse IP | Après 20 échecs sur 15 minutes glissantes, les nouvelles tentatives renvoient `RATE_LIMITED`, quel que soit le compte. Cela borne aussi les essais répartis sur plusieurs identifiants. |
| Mémoire par instance | Jusqu'à 4 vérifications simultanées, soit au plus 256 Mio de mémoire Argon2 hors surcoût JVM pour ces appels. La saturation renvoie `RATE_LIMITED` avant ouverture de connexion. |

Les seuils par compte et par adresse reprennent SR-AUT-03 et le §6.1. Leur réglage est versionné dans la migration. Le délai plafonné évite une suspension permanente imposée par un tiers. La table temporaire au sens fonctionnel `tt.login_failure` est persistante. Elle contient l'adresse réseau et une empreinte SHA-256 du courriel normalisé pour le comptage, qui reste une donnée pseudonyme. Les décisions ignorent immédiatement les entrées de plus de 15 minutes. Les refus suivants purgent les anciennes entrées par lots de 64. En l'absence de nouveaux refus, la purge physique peut être ultérieure. Les serveurs derrière un proxy devront établir l'adresse d'origine à partir d'un relais explicitement approuvé.

## Demande de SHA3-224 côté client

La demande de préhachage vient de la consigne du professeur rapportée pour ce lot. Les documents versionnés consultés prescrivent Argon2id. Le service de cette étape traite le mot de passe directement. BASIC sur HTTPS et les endpoints existants continuent de fonctionner.

Un SHA3-224 déterministe transmis tel quel devient un secret de connexion réutilisable. Celui qui obtient cette valeur pourrait la présenter au serveur, et comparer hors ligne des hypothèses à ce condensat rapide. TLS reste nécessaire. Un script compromis dans le navigateur peut lire la saisie avant le calcul. Un condensat seul empêche aussi le serveur de vérifier la longueur du mot de passe d'origine.

Si le préhachage est confirmé, il faudra spécifier l'encodage, la normalisation et la représentation exacte des 28 octets, versionner le profil d'entrée, conserver le sel et Argon2id côté serveur et définir le renouvellement des vérificateurs existants. Accepter indifféremment mot de passe et condensat pour un même compte introduirait deux secrets de connexion possibles. Cette décision précède l'intégration avec le frontend.
