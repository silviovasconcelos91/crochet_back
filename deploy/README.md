# Déploiement

La pipeline (`.github/workflows/ci.yml`) déploie chaque push sur `master` sur le VPS, dans
`/opt/crochet-back`. Ce dossier contient la pile de production : l'app (image native) derrière
Caddy, qui obtient et renouvelle le certificat HTTPS.

## Préparer le VPS (une fois)

1. Docker et le plugin compose installés ; un utilisateur de déploiement membre du groupe `docker`.
2. Un enregistrement DNS **A uniquement** (pas de AAAA) de votre domaine vers le VPS ; ports 80 et
   443 ouverts. Sans IPv6 activé dans Docker, les clients IPv6 arriveraient tous avec l'adresse de
   la passerelle Docker et partageraient un seul quota de la limite de tentatives de connexion.
3. `sudo mkdir -p /opt/crochet-back && sudo chown <utilisateur> /opt/crochet-back`
4. Créer `/opt/crochet-back/.env` (jamais versionné) :

       PGHOST=host.docker.internal
       PGPORT=5432
       PGDATABASE=crochet
       PGUSER=crochet
       PGPASSWORD=...
       DOMAIN=api.mondomaine.fr
       CROCHET_CORS_ALLOWEDORIGINS=https://app.mondomaine.fr

   Compose interprète les `$` du fichier : un mot de passe qui en contient s'écrit entre apostrophes
   (`PGPASSWORD='mot$de$passe'`). Le front doit être sur le même site que l'API (même domaine
   parent) : le cookie de session est `SameSite=Lax`.

   **Postgres installé sur le VPS lui-même** : `host.docker.internal` désigne l'hôte vu depuis le
   conteneur (l'interface `docker0`, en général `172.17.0.1`), mais les connexions arrivent depuis
   le réseau du compose (`172.18.0.0/16` ou voisin). Il faut donc :
   - dans `postgresql.conf` : `listen_addresses = 'localhost,172.17.0.1'` (Postgres doit démarrer
     après Docker pour que cette adresse existe, ou utiliser `'*'` avec un pare-feu) ;
   - dans `pg_hba.conf` : `host crochet crochet 172.16.0.0/12 scram-sha-256` ;
   - si un pare-feu tourne (ufw) : autoriser le port 5432 depuis `172.16.0.0/12`.
5. Une clé SSH dédiée au déploiement : `ssh-keygen -t ed25519 -f crochet-deploy -N ""`, la clé
   publique dans `~/.ssh/authorized_keys` de l'utilisateur de déploiement.

## Secrets et variables GitHub

Au niveau du **dépôt** (Settings → Secrets and variables → Actions), utilisés pour publier l'image :

| Nom | Type | Valeur |
|---|---|---|
| `DOCKERHUB_USERNAME` | variable | votre identifiant Docker Hub (non secret : il apparaît dans le nom de l'image) |
| `DOCKERHUB_TOKEN` | secret | un access token Docker Hub (Read & Write) |

Dans l'**environnement `production`**, utilisés seulement par le job de déploiement (une règle
d'approbation éventuelle sur cet environnement n'est donc demandée qu'une fois par release) :

| Nom | Type | Valeur |
|---|---|---|
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

Une migration Flyway appliquée ne se défait pas. Flyway laisse démarrer une version plus ancienne
sur un schéma plus récent, mais la validation Hibernate (`ddl-auto: validate`) ou le code de cette
version échouent si une table ou une colonne qu'ils utilisent a disparu ou changé. Chaque migration
doit donc rester compatible avec la version précédente (ajouter avant de supprimer, en deux
déploiements), pour que le retour arrière reste possible.

**Exception : la version qui introduit les pseudos (migrations V7 et V9) est à sens unique.** V7 vide les
comptes, les patterns et les sessions de façon irréversible, puis ajoute un pseudo obligatoire ; V9 change
la clé de l'avancement. Il ne faut donc pas revenir à une image antérieure à cette version. Avant de la
déployer, faire une sauvegarde de la base. Elle n'est pas dans `compose.yaml` : Postgres tourne sur le VPS
(voir `PGHOST`), donc :

    pg_dump -h localhost -U crochet -Fc crochet > crochet-avant-v7.dump

