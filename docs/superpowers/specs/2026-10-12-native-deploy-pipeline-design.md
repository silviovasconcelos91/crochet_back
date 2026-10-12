# Pipeline de déploiement natif — Design

Date : 2026-10-12
Repo : `crochet_back` (Spring Boot 4.1.1, Java 21, GraalVM native-maven-plugin déjà dans le pom)

## 1. Objectif

À chaque push sur `master`, une pipeline GitHub Actions teste l'application, la compile en image
native GraalVM, vérifie le binaire natif, publie l'image sur Docker Hub et la déploie sur un VPS,
derrière un HTTPS automatique.

### Ce qui est demandé

- Pipeline GitHub Actions ; les PR vers `master` ne lancent que les tests.
- Image native (amd64), publiée sur **Docker Hub**.
- Déploiement sur un **VPS amd64 via SSH + Docker** ; le compose de prod est **versionné dans le
  repo** et copié par la pipeline.
- **Postgres existe déjà** : l'app reçoit seulement `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
- Pas de reverse proxy existant : **Caddy** est ajouté (HTTPS Let's Encrypt automatique).

### Critères de succès

- Un push sur `master` met la nouvelle version en ligne en HTTPS sans action manuelle.
- Un test rouge, une compilation native ratée ou un smoke test natif rouge ne publie et ne
  déploie rien.
- Le serveur tourne sur une version identifiée (tag `sha-<commit>`), et un retour arrière se fait
  en une commande.

### Hypothèses

- Docker (avec le plugin compose) est installé sur le VPS ; l'utilisateur SSH de déploiement peut
  lancer `docker`.
- Postgres est joignable depuis un conteneur du VPS (sur l'hôte via `host.docker.internal`, ou
  ailleurs).
- Le front est servi sur le même site que l'API (ex : `app.mondomaine.fr` et
  `api.mondomaine.fr`) : le cookie de session est `SameSite=Lax`.
- Un nom de domaine pointe vers le VPS (enregistrement A), ports 80 et 443 ouverts.

### Hors périmètre

Déploiement sans interruption, monitoring/logs centralisés (`docker logs` suffit), sauvegardes
Postgres, déploiement du front, création du repo GitHub et premier push (actions sortantes laissées à
l'utilisateur), health check Actuator.

## 2. Pipeline — `.github/workflows/ci.yml`

Un seul workflow, trois jobs enchaînés.

| Job | Déclencheur | Rôle |
|---|---|---|
| `test` | PR vers `master`, push sur `master` | Temurin 21 + cache Maven, `./mvnw -B verify` (suite complète, Testcontainers) |
| `native` | push sur `master`, après `test` | GraalVM 21 (`graalvm/setup-graalvm`) + cache Maven, `./mvnw -B -Pnative -DskipTests native:compile`, smoke test du binaire, `docker build`, push Docker Hub |
| `deploy` | après `native`, environnement GitHub `production` | copie `deploy/` sur le VPS (SCP), écrit `release.env`, lance `deploy.sh` en SSH, vérifie `https://<domaine>/api/auth/csrf` → 204 |

- **Smoke test natif** (`ci/smoke-test.sh`, curl) : Postgres 17 en conteneur de service, binaire
  lancé avec `SESSION_COOKIE_SECURE=false` ; parcours : CSRF → inscription → `/api/me` →
  déconnexion → connexion → création d'un pattern → avancement → lecture du pattern. Il couvre ce
  que les tests JVM ne voient pas en natif : session sérialisée en base, Jackson, Hibernate, Flyway.
  Le script prend l'URL de base en argument et sert aussi en local (JVM ou natif).
- **Tags d'image** : `latest` et `sha-<commit court>` ; le déploiement utilise le tag `sha-…`.
- **`concurrency: production`** (sans annulation) : deux déploiements ne se chevauchent jamais.
- **Disponibilité** : `/api/auth/csrf` répond 204 dès que l'app sert des requêtes (ne vérifie pas
  la base ; une base injoignable empêche de toute façon le démarrage via Flyway).

## 3. Image et adaptations natives

- **`Dockerfile`** (racine) : base `gcr.io/distroless/base-debian12:nonroot` (glibc, sans shell),
  copie `target/crochet_back`, utilisateur non-root, `EXPOSE 8080`, `ENTRYPOINT` sur le binaire.
- **`.dockerignore`** : exclut tout sauf `target/crochet_back`.
- **`config/NativeHints`** (`RuntimeHintsRegistrar`) : déclare la sérialisation Java
  d'`AccountPrincipal` (stocké dans la session JDBC). Les types Spring Security ont leurs propres
  hints ; Flyway, Hibernate, Jackson et la validation sont couverts par l'AOT de Spring Boot. Tout
  hint manquant révélé par le smoke test (ex : ressources `db/migration`) est ajouté ici.
- **Reproductible en local** : même binaire et même image que la CI.

## 4. Côté VPS — `deploy/`

Copié dans `/opt/crochet-back` à chaque déploiement.

- **`compose.yaml`**
  - `app` : `${DOCKERHUB_IMAGE}:${IMAGE_TAG}`, `restart: unless-stopped`, `env_file: .env`,
    `extra_hosts: host.docker.internal:host-gateway`, **aucun port publié** (joignable par Caddy
    seulement).
  - `caddy` : `caddy:2`, ports 80, 443 (TCP et UDP), volumes `caddy_data`/`caddy_config`, variable
    `DOMAIN`.
- **`Caddyfile`** : `{$DOMAIN} { reverse_proxy app:8080 }`. Caddy envoie `X-Forwarded-For` et
  `X-Forwarded-Proto` ; son IP (réseau Docker 172.16/12) fait partie des proxys internes que Tomcat
  croit (`forward-headers-strategy: native`), donc l'app voit l'IP réelle du client (limite de
  tentatives) et le schéma HTTPS. L'app n'étant pas exposée, ces en-têtes ne sont pas falsifiables.
- **`deploy.sh`** : `docker compose --env-file .env --env-file release.env pull` puis `up -d`.
  Sert aussi aux redémarrages manuels et au retour arrière (ancien tag dans `release.env`).
- **`.env`** (créé une fois à la main sur le serveur, jamais versionné) : `DB_URL`, `DB_USERNAME`,
  `DB_PASSWORD`, `DOMAIN`, `CROCHET_CORS_ALLOWEDORIGINS`.
- **`release.env`** (écrit par la pipeline) : `DOCKERHUB_IMAGE`, `IMAGE_TAG`.
- **`README.md`** : préparation du VPS, contenu du `.env`, secrets GitHub, retour arrière, règle
  des migrations compatibles avec la version précédente.

### Secrets et variables GitHub (environnement `production`)

| Nom | Type | Usage |
|---|---|---|
| `DOCKERHUB_USERNAME` | secret | login Docker Hub, préfixe de l'image |
| `DOCKERHUB_TOKEN` | secret | token d'accès Docker Hub (pas le mot de passe) |
| `VPS_HOST`, `VPS_USER` | secrets | cible SSH |
| `VPS_SSH_KEY` | secret | clé privée dédiée au déploiement |
| `VPS_KNOWN_HOSTS` | secret | empreinte du serveur épinglée (pas de confiance au premier contact) |
| `DEPLOY_DOMAIN` | variable | vérification HTTPS finale |

L'image est publique (elle ne contient aucun secret) ; si elle devient privée, un `docker login`
unique sur le VPS suffit.

## 5. Vérification et échecs

### Avant le premier push (pas de remote)

- Build natif Linux amd64 dans `ghcr.io/graalvm/native-image-community:21` (sous Windows,
  `native-image` produirait un `.exe` inutilisable dans l'image).
- Smoke test sur ce binaire contre le Postgres local (port 55432) ; hints manquants corrigés.
- `ci/smoke-test.sh` validé aussi contre l'app JVM.
- `actionlint` (via Docker) sur le workflow.
- `docker build` puis lancement de l'image avec la configuration de prod.

### Échecs

| Situation | Effet |
|---|---|
| Test, build natif ou smoke test rouge | rien n'est publié ni déployé |
| L'app ne démarre pas sur le VPS (base, migration) | vérification HTTPS en échec, job rouge ; retour arrière via `release.env` + `deploy.sh` |
| Retour arrière après une migration | une migration appliquée ne se défait pas : les migrations doivent rester compatibles avec la version précédente |
| Redémarrage | coupure d'environ une seconde (natif) |
