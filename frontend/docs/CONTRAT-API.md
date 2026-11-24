# Contrat frontend / backend (état du dépôt de Cheima)

## Déjà fourni par le backend et utilisé par le frontend
| Route | Contrat | Utilisation |
|---|---|---|
| `POST /api/auth/login` | corps `{email, password, otp?}` ; 200 `{access_token, token_type}` ; 202 `{next:"totp"}` ; 401 uniforme ; 429 ; 503 | Page de connexion (2 temps si TOTP) |
| `POST /api/auth/logout` | 204, jeton révoqué | Bouton « Se déconnecter » |
| `POST /api/auth/factor/revoke` | 204 ; 403 si preuve TOTP > 5 min | Page « Mon compte » |
| `GET /api/.well-known/jwks.json` | clés publiques Ed25519 | Non utilisé (le serveur vérifie les jetons) |

Jeton : PASETO v4.public de 15 min, claims `sub, org, exp, auth_time, amr, factor_id`. Le frontend le garde en
mémoire et n'en lit que `exp` et `auth_time` (sans vérifier la signature).

## Manquant côté backend (à demander à Cheima)
| Besoin | Proposition |
|---|---|
| Rôles et nom de l'utilisateur | `GET /api/auth/me` -> `{displayName, roles:["PROPRIETAIRE", ...]}`. Sans lui, l'interface n'affiche que le tableau de bord et « Mon compte ». |
| Refresh du jeton | Aucun : après 15 min le frontend déconnecte. À confirmer. |
| ALTCHA | Aucun endpoint de défi. |
| Hachage SHA3-224 côté client | Non pris en charge : le backend attend `password` en clair sur HTTPS. |
| Ressources métier | `/datasets`, `/distributions`, `/copies/mine`, `/investigations`, `/admin/accounts`, `/audit/events` : à définir. Les routes de `src/api/services.ts` sont des hypothèses. |

## Lancer avec le vrai login
1. WildFly + PostgreSQL démarrés (README du dépôt), HTTPS sur `https://localhost:8443`.
2. `.env.local` : `VITE_MOCK_AUTH=false` et `VITE_USE_MOCKS=true` (login réel, données métier simulées).
3. `npm run dev` : le proxy `/api` envoie vers `https://localhost:8443`.
