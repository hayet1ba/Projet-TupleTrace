# Servir le frontend depuis WildFly

1. `npm run build` produit `dist/` (shell statique + `sw.js` + `manifest.webmanifest`).
2. Copier `dist/` vers le chemin de l'hôte par défaut défini dans le `.properties` de l'environnement
   (`default.path`, ex. `/var/opt/wildfly/www/root` en prod).
3. Lancer le script `.cli` de l'encadrant :
   `$JBOSS_HOME/bin/jboss-cli.sh --connect --controller=remote+https://hote:9993 --file=default-host.cli --properties=default-host-dev.properties`
4. Routes React : WildFly doit renvoyer `index.html` pour tout chemin non-API (ex. `/datasets/12`). À vérifier avec le script `.cli`
   (filtre de réécriture Undertow ou page d'erreur 404 → `index.html`), en excluant `/api/**`.
5. `VITE_USE_MOCKS=false` avant le build de production (aucun mock dans le build livré).

## Vérifications après déploiement
- En-têtes sur `/api/**` : `Cache-Control: no-store`. Sur le shell : HSTS actif.
- DevTools > Application > Storage : `localStorage` et `sessionStorage` vides après connexion.
- Se connecter, naviguer, se déconnecter : Cache Storage vide (la déconnexion purge les caches), aucune réponse `/api` dedans.
- Mode hors ligne (DevTools > Network > Offline) : seule l'interface s'affiche, avec le bandeau hors ligne.
