# Déploiement

La pipeline (`.github/workflows/ci.yml`) déploie chaque push sur `master` sur le VPS, dans
`/opt/crochet-back`. Ce dossier contient la pile de production : l'app (image native) derrière
Caddy, qui obtient et renouvelle le certificat HTTPS.

## Préparer le VPS (une fois)

1. Docker et le plugin compose installés ; un utilisateur de déploiement membre du groupe `docker`.
2. Un enregistrement DNS A de votre domaine vers le VPS ; ports 80 et 443 ouverts.
3. `sudo mkdir -p /opt/crochet-back && sudo chown <utilisateur> /opt/crochet-back`
4. Créer `/opt/crochet-back/.env` (jamais versionné) :

       DB_URL=jdbc:postgresql://host.docker.internal:5432/crochet
       DB_USERNAME=crochet
       DB_PASSWORD=...
       DOMAIN=api.mondomaine.fr
       CROCHET_CORS_ALLOWEDORIGINS=https://app.mondomaine.fr

   `host.docker.internal` désigne le VPS lui-même (Postgres installé sur l'hôte, qui doit alors
   écouter sur l'interface Docker et l'autoriser dans `pg_hba.conf`). Le front doit être sur le même
   site que l'API (même domaine parent) : le cookie de session est `SameSite=Lax`.
5. Une clé SSH dédiée au déploiement : `ssh-keygen -t ed25519 -f crochet-deploy -N ""`, la clé
   publique dans `~/.ssh/authorized_keys` de l'utilisateur de déploiement.

## Secrets GitHub (environnement `production`)

| Nom | Type | Valeur |
|---|---|---|
| `DOCKERHUB_USERNAME` | secret | votre identifiant Docker Hub |
| `DOCKERHUB_TOKEN` | secret | un access token Docker Hub (Read & Write) |
| `VPS_HOST` | secret | adresse du VPS |
| `VPS_USER` | secret | utilisateur de déploiement |
| `VPS_SSH_KEY` | secret | contenu de la clé privée `crochet-deploy` |
| `VPS_KNOWN_HOSTS` | secret | sortie de `ssh-keyscan -t ed25519 <VPS_HOST>`, vérifiée contre l'empreinte du serveur |
| `DEPLOY_DOMAIN` | variable | `api.mondomaine.fr` |

## Opérations

- Redémarrer : `sh /opt/crochet-back/deploy.sh`
- Revenir à une version : mettre l'ancien tag (`IMAGE_TAG=sha-xxxxxxx`, voir Docker Hub) dans
  `release.env`, puis `sh deploy.sh`.
- Logs : `docker compose --env-file .env --env-file release.env logs -f app`

## Règle pour les migrations

Une migration Flyway appliquée ne se défait pas : une version plus ancienne refusera de démarrer sur
un schéma qu'elle ne connaît pas. Chaque migration doit donc rester compatible avec la version
précédente (ajouter avant de supprimer, en deux déploiements), pour que le retour arrière reste
possible.
