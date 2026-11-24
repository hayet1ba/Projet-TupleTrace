# PostgreSQL 17 et WildFly 42

Les commandes Bash ci-dessous se lancent à la racine du dépôt avec JDK 25, Docker Compose et le wrapper Maven. Sous PowerShell, le wrapper est `.\mvnw.cmd`.

## Base de développement

Fournir dans l'environnement trois secrets distincts, `POSTGRES_PASSWORD` pour l'initialisation du cluster, `TT_MIGRATION_PASSWORD` pour Flyway et `TT_APP_PASSWORD` pour le pool JDBC. Pour un cluster jetable, ils peuvent être générés dans le shell.

```bash
export POSTGRES_PASSWORD="$(openssl rand -hex 32)"
export TT_MIGRATION_PASSWORD="$(openssl rand -hex 32)"
export TT_APP_PASSWORD="$(openssl rand -hex 32)"
export TT_DATABASE=tupletrace
export TT_DB_PORT=5432
export TT_JDBC_URL="jdbc:postgresql://127.0.0.1:$TT_DB_PORT/$TT_DATABASE"

docker compose -p tupletrace-dev -f ops/postgres/compose.yaml up -d --wait
sh ./mvnw -f db/pom.xml flyway:migrate flyway:validate
```

Compose publie le port sur la boucle locale. Sur un volume neuf, PostgreSQL exécute `bootstrap.sql` pour créer les rôles, le schéma `tt` et l'extension `btree_gist`. Les connexions utilisent SCRAM-SHA-256. Pour réutiliser le volume, conserver les secrets dans le stockage sécurisé de l'environnement. Une modification des variables de démarrage ne change pas les mots de passe des rôles existants.

Flyway applique les fichiers de `db/migration/` avec `tt_migrator`. Ce compte possède le schéma dédié. Le compte `tt_app` lit les métadonnées et ajoute des événements par la fonction `tt.append_audit_event`. Le rôle SQL sans connexion `tt_audit_reader` permet de déléguer séparément la lecture du journal à une identité d'audit. Un rôle métier dans `role_assignment` ne confère aucun rôle SQL.

## Pool JDBC WildFly

Préparer le pilote versionné depuis le POM de persistance.

```bash
sh ./mvnw -f db/pom.xml -Ppostgres-it dependency:copy@prepare-wildfly-driver
cp db/target/wildfly-driver/postgresql.jar "$JBOSS_HOME/standalone/deployments/"
```

Démarrer WildFly 42 en mode standalone avec `TT_JDBC_URL` et `TT_APP_PASSWORD` dans son environnement. Omettre les identifiants d'administration et de migration dans le processus WildFly. Après le déploiement de `postgresql.jar`, appliquer le script à ce serveur.

```bash
"$JBOSS_HOME/bin/jboss-cli.sh" --connect --file=ops/wildfly/datasource.cli
"$JBOSS_HOME/bin/jboss-cli.sh" --connect --file=ops/wildfly/authentication.cli
```

Le script crée la datasource JTA `java:/jdbc/TupleTraceDS` au premier passage et teste le pool à chaque exécution. La configuration serveur conserve les expressions d'environnement. Le pilote est déployé sous le nom stable `postgresql.jar`. L'endpoint de santé du lot 1 continue de vérifier que l'API répond. Le test JDBC est indépendant.

## Tests sur base vierge

Utiliser un projet Compose dédié avec un volume neuf et le nom de base réservé `tupletrace_test`. Dans un shell avec les trois secrets de test initialisés, lancer par exemple les commandes suivantes.

```bash
export TT_DATABASE=tupletrace_test TT_DB_PORT=55432
export TT_JDBC_URL="jdbc:postgresql://127.0.0.1:$TT_DB_PORT/$TT_DATABASE"
docker compose -p tupletrace-it -f ops/postgres/compose.yaml up -d --wait
sh ./mvnw -B -ntp -Ppostgres-it clean verify
```

La suite vérifie la version majeure 17, le nom de la base et l'absence de tables dans le schéma avant toute migration. Elle applique les migrations, les valide, puis les rejoue sur la même base. Pour répéter toute la suite, utiliser un nouveau volume de test. Les rapports JUnit sont dans `db/target/failsafe-reports/`. La CI emploie un volume propre au runner puis déploie le pilote et le WAR pour vérifier la connexion et les routes HTTPS.
