# Native Deploy Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On every push to `master`, a GitHub Actions pipeline tests the app, compiles it to a GraalVM native image, smoke-tests the native binary, pushes the image to Docker Hub and deploys it to an amd64 VPS behind Caddy (automatic HTTPS).

**Architecture:** One workflow with three jobs (`test` → `native` → `deploy`). The native binary is compiled on the runner with `graalvm/setup-graalvm`, smoke-tested against a Postgres service container with a curl script, then copied into a distroless image. The deploy job copies a versioned `deploy/` folder (compose, Caddyfile, script) to the VPS over SSH with a pinned host key and restarts the stack on the exact `sha-<commit>` tag.

**Tech Stack:** GitHub Actions, GraalVM CE 25 (bytecode Java 21) (native-maven-plugin, Spring Boot AOT), Docker / Docker Hub, distroless `base-debian12`, Docker Compose, Caddy 2, Bash + curl.

**Spec:** `docs/superpowers/specs/2026-10-12-native-deploy-pipeline-design.md`

## Global Constraints

- Image name: `<DOCKERHUB_USERNAME>/crochet-back`; tags `latest` and `sha-<first 7 chars of the commit>`. The VPS always runs a `sha-…` tag.
- Native binary: `target/crochet_back` (artifactId), built with `./mvnw -B -Pnative -DskipTests native:compile`, Linux amd64.
- Runtime image base: `gcr.io/distroless/base-debian12:nonroot`; the app listens on 8080 and is never published on the VPS host.
- The app is configured only through environment variables: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SESSION_COOKIE_SECURE` (default `true`), `CROCHET_CORS_ALLOWEDORIGINS`.
- VPS directory: `/opt/crochet-back`. `.env` there is created by hand and never versioned; `release.env` is written by the pipeline (`DOCKERHUB_IMAGE`, `IMAGE_TAG`).
- GitHub environment `production` holds secrets `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS` and variable `DEPLOY_DOMAIN`.
- SSH: plain `ssh`/`scp` with the key and `known_hosts` from secrets and `StrictHostKeyChecking=yes`; no third-party SSH action receives secrets.
- Shell scripts are LF (`.gitattributes`) and are invoked as `bash script.sh` / `sh script.sh`, never relying on the executable bit.
- The repo has no remote: nothing is pushed. Docker is required locally; the host port 5432 is taken by a native Postgres, so local runs use a Postgres container on **55432**.
- Run Maven from the repo root with `./mvnw` (Git Bash). On Windows, `native-image` is only run inside the `ghcr.io/graalvm/native-image-community:25` container.

## Review Focus

1. A session stored in Postgres by the native binary must deserialize on the next request (Java serialization in native needs hints) → pinned by the smoke test's `/api/me` after register and after login (Tasks 1, 3).
2. Behind Caddy, the app must see HTTPS and the real client IP, so `Secure` cookies are accepted and the login throttle keys on the client → pinned by running the smoke test through `https://localhost` with production cookie settings (Task 4).
3. A failed deploy (app not answering) must make the job fail, not silently succeed → pinned by the final HTTPS check loop that exits 1 (Task 5).
4. Two pushes in quick succession must not deploy out of order → workflow-level concurrency per ref without cancellation on `master` (Task 5, checked by actionlint and review).
5. Re-running `deploy.sh` by hand after a reboot must restart the same release → `release.env` persists the tag and `deploy.sh` reads it (Task 4).

---

## File Structure

| File | Responsibility |
|---|---|
| `.gitattributes` (modify) | force LF on `*.sh` |
| `.gitignore` (modify) | ignore `deploy/release.env`, `deploy/.env` |
| `ci/smoke-test.sh` (create) | end-to-end curl check of a running instance (URL argument) |
| `src/main/java/vasconcelos/silvio/crochet_back/config/NativeHints.java` (create) | `RuntimeHintsRegistrar` for native-only needs (session serialization) |
| `src/main/java/vasconcelos/silvio/crochet_back/CrochetBackApplication.java` (modify) | `@ImportRuntimeHints(NativeHints.class)` |
| `src/test/java/vasconcelos/silvio/crochet_back/config/NativeHintsTest.java` (create) | asserts the hints |
| `Dockerfile`, `.dockerignore` (create) | runtime image around the native binary |
| `deploy/compose.yaml`, `deploy/Caddyfile`, `deploy/deploy.sh`, `deploy/README.md` (create) | VPS stack and its documentation |
| `.github/workflows/ci.yml` (create) | the pipeline |

---

### Task 1: Smoke test script

**Files:**
- Create: `ci/smoke-test.sh`
- Modify: `.gitattributes`

**Interfaces:**
- Produces: `bash ci/smoke-test.sh <base-url>`; exits 0 when the whole flow passes, 1 with the failing step and response body otherwise. Extra curl options come from the `CURL_OPTS` environment variable (e.g. `-k` for a local CA). Waits up to 60 s for the instance to answer.

- [ ] **Step 1: Force LF on shell scripts**

Append to `.gitattributes`:

```
*.sh text eol=lf
```

- [ ] **Step 2: Write the script**

`ci/smoke-test.sh`:

```bash
#!/usr/bin/env bash
# End-to-end check of a running crochet_back instance: CSRF, register, session, logout, login,
# pattern creation and progress. Usage: ci/smoke-test.sh <base-url>
# Extra curl options can be given in CURL_OPTS (e.g. CURL_OPTS=-k for a local certificate).
set -euo pipefail

BASE="${1:?usage: smoke-test.sh <base-url>}"
read -r -a EXTRA <<< "${CURL_OPTS:-}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
JAR="$WORK/cookies"
BODY="$WORK/body"
EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASSWORD="smoke-test-password"
touch "$JAR"

fail() {
    echo "FAIL: $1" >&2
    [ -s "$BODY" ] && cat "$BODY" >&2 && echo >&2
    exit 1
}

xsrf() {
    awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$JAR"
}

# call <method> <path> <expected status> [json body]
call() {
    local method="$1" path="$2" expected="$3" data="${4:-}" status
    local args=(-s "${EXTRA[@]}" -o "$BODY" -w '%{http_code}' -b "$JAR" -c "$JAR" -X "$method"
        -H "X-XSRF-TOKEN: $(xsrf)")
    if [ -n "$data" ]; then
        args+=(-H 'Content-Type: application/json' --data "$data")
    fi
    status="$(curl "${args[@]}" "$BASE$path")" || fail "$method $path: no response"
    [ "$status" = "$expected" ] || fail "$method $path: expected $expected, got $status"
    echo "ok   $method $path -> $status"
}

for _ in $(seq 60); do
    if [ "$(curl -s "${EXTRA[@]}" -o /dev/null -w '%{http_code}' "$BASE/api/auth/csrf")" = 204 ]; then
        break
    fi
    sleep 1
done

CREDENTIALS="{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
call GET  /api/auth/csrf      204
call POST /api/auth/register  201 "$CREDENTIALS"
call GET  /api/me             200
grep -q "\"email\":\"$EMAIL\"" "$BODY" || fail "/api/me does not return the registered email"
call POST /api/auth/logout    204
call GET  /api/me             401
call POST /api/auth/login     200 "$CREDENTIALS"
call GET  /api/me             200
call POST /api/patterns       201 '{"title":"Smoke","steps":[{"title":"Corps","rows":[{"number":"1","value":"6ms (6)"},{"number":"2","value":"6aug (12)"}]}]}'
ID="$(grep -o '"id":[0-9]*' "$BODY" | head -1 | cut -d: -f2)"
[ -n "$ID" ] || fail "no pattern id in the creation response"
call PUT  "/api/patterns/$ID/progress" 200 '{"status":"IN_PROGRESS","stepIndex":0,"rowIndex":1}'
call GET  "/api/patterns/$ID" 200
grep -q '"status":"IN_PROGRESS"' "$BODY" || fail "the pattern does not show its progress"
call GET  /api/patterns       200
echo "Smoke test passed against $BASE"
```

- [ ] **Step 3: Start a local Postgres on 55432**

Run:

```bash
docker run -d --name crochet-smoke-pg -e POSTGRES_DB=crochet -e POSTGRES_USER=crochet \
  -e POSTGRES_PASSWORD=crochet -p 55432:5432 postgres:17
```

Expected: a container id. (If the name already exists from an earlier run: `docker start crochet-smoke-pg`.)

- [ ] **Step 4: Watch the script fail with no app running**

Run: `bash ci/smoke-test.sh http://localhost:8080`
Expected: after ~60 s, `FAIL: GET /api/auth/csrf: ...` and exit code 1 (proves the script detects a dead instance).

- [ ] **Step 5: Run it against the JVM app**

Run the app in the background, then the script:

```bash
DB_URL=jdbc:postgresql://localhost:55432/crochet SESSION_COOKIE_SECURE=false \
  ./mvnw -q spring-boot:run > /tmp/jvm-app.log 2>&1 &
bash ci/smoke-test.sh http://localhost:8080
```

Expected: one `ok` line per call and `Smoke test passed against http://localhost:8080`, exit 0. Then stop the app (kill the `spring-boot:run` java process).

- [ ] **Step 6: Commit**

```bash
git add .gitattributes ci/smoke-test.sh
git commit -m "ci: add an end-to-end smoke test script"
```

---

### Task 2: Native runtime hints

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/config/NativeHints.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/CrochetBackApplication.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/config/NativeHintsTest.java`

**Interfaces:**
- Produces: `NativeHints implements RuntimeHintsRegistrar`, imported on the application class. Task 3 adds any further hint it discovers to this class and to its test.

- [ ] **Step 1: Write the failing test**

```java
package vasconcelos.silvio.crochet_back.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;

import static org.assertj.core.api.Assertions.assertThat;

class NativeHintsTest {

    @Test
    void sessionPrincipalCanBeJavaSerialized() {
        RuntimeHints hints = new RuntimeHints();

        new NativeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.serialization().onType(AccountPrincipal.class)).accepts(hints);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -q test -Dtest=NativeHintsTest`
Expected: compilation failure, `cannot find symbol ... NativeHints`.

- [ ] **Step 3: Implement**

`NativeHints.java`:

```java
package vasconcelos.silvio.crochet_back.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;

/**
 * What the native image cannot discover on its own. Spring Session JDBC stores the security context
 * with Java serialization, so the principal it contains must be registered for serialization.
 */
public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.serialization().registerType(AccountPrincipal.class);
    }
}
```

In `CrochetBackApplication.java`, add `@ImportRuntimeHints(NativeHints.class)` under `@SpringBootApplication` (import `org.springframework.context.annotation.ImportRuntimeHints` and `vasconcelos.silvio.crochet_back.config.NativeHints`).

- [ ] **Step 4: Run the test and the suite**

Run: `./mvnw -q test -Dtest=NativeHintsTest` then `./mvnw -q test`
Expected: PASS; full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/config/NativeHints.java \
  src/main/java/vasconcelos/silvio/crochet_back/CrochetBackApplication.java \
  src/test/java/vasconcelos/silvio/crochet_back/config/NativeHintsTest.java
git commit -m "feat: register native hints for the session principal"
```

---

### Task 3: Runtime image and native verification

**Files:**
- Create: `Dockerfile`, `.dockerignore`
- Modify (only if the smoke test reveals a gap): `config/NativeHints.java`, `config/NativeHintsTest.java`

**Interfaces:**
- Consumes: `ci/smoke-test.sh` (Task 1), `NativeHints` (Task 2), Postgres container on 55432 (Task 1).
- Produces: `docker build -t <name> .` packages `target/crochet_back`; the image runs as non-root on 8080.

- [ ] **Step 1: Write the image files**

`Dockerfile`:

```dockerfile
# Packages the native binary built by `./mvnw -Pnative native:compile` (Linux amd64).
FROM gcr.io/distroless/base-debian12:nonroot
COPY target/crochet_back /app/crochet_back
EXPOSE 8080
ENTRYPOINT ["/app/crochet_back"]
```

`.dockerignore`:

```
*
!target/crochet_back
```

- [ ] **Step 2: Build the Linux native binary in the GraalVM container**

Run (Git Bash; `MSYS_NO_PATHCONV` keeps the container paths intact):

```bash
MSYS_NO_PATHCONV=1 docker run --rm --entrypoint bash -v "$(pwd -W)":/app -v "$HOME/.m2":/root/.m2 \
  -w /app ghcr.io/graalvm/native-image-community:25 -c './mvnw -B -Pnative -DskipTests native:compile'
```

Expected: `BUILD SUCCESS` and a Linux ELF `target/crochet_back` (`file target/crochet_back` → `ELF 64-bit LSB executable, x86-64`). This takes several minutes. If native-image is killed (exit 137), raise the Docker Desktop memory limit to 8 GB and retry.

- [ ] **Step 3: Build and run the image against the local Postgres**

```bash
docker build -t crochet-back:local .
docker run -d --name crochet-native -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:55432/crochet \
  -e SESSION_COOKIE_SECURE=false crochet-back:local
```

Expected: `docker logs crochet-native` shows Flyway migrations validated/applied and `Started CrochetBackApplication in 0.xxx seconds`.

- [ ] **Step 4: Smoke-test the native image**

Run: `bash ci/smoke-test.sh http://localhost:8080`
Expected: `Smoke test passed`. If a step fails, read `docker logs crochet-native`:
- a `MissingReflectionRegistrationError` / `MissingSerializationRegistrationError` / `NotSerializableException` names the missing type → add it to `NativeHints` (`hints.serialization().registerType(...)` or `hints.reflection().registerType(...)`), add the matching assertion to `NativeHintsTest`, run `./mvnw -q test -Dtest=NativeHintsTest`, and repeat Steps 2-4;
- Flyway reporting no migrations → add `hints.resources().registerPattern("db/migration/*")` with its test, and repeat.
Record each added hint as a ruling in the ledger.

- [ ] **Step 5: Check the image runs as non-root and clean up**

Run: `docker inspect --format '{{.Config.User}}' crochet-back:local`
Expected: `65532` (distroless `nonroot`). Then `docker rm -f crochet-native`.

- [ ] **Step 6: Commit**

```bash
git add Dockerfile .dockerignore src/main/java/vasconcelos/silvio/crochet_back/config src/test/java/vasconcelos/silvio/crochet_back/config
git commit -m "build: package the native binary in a distroless image"
```

---

### Task 4: VPS stack (compose, Caddy, deploy script, docs)

**Files:**
- Create: `deploy/compose.yaml`, `deploy/Caddyfile`, `deploy/deploy.sh`, `deploy/README.md`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: image `crochet-back:local` (Task 3), `ci/smoke-test.sh` (Task 1).
- Produces: `/opt/crochet-back/deploy.sh` reads `.env` and `release.env` from its own folder; `compose.yaml` needs `DOCKERHUB_IMAGE`, `IMAGE_TAG`, `DOMAIN`. Task 5 copies `compose.yaml`, `Caddyfile`, `deploy.sh`, `release.env` and runs `sh deploy.sh`.

- [ ] **Step 1: Write the stack**

`deploy/compose.yaml`:

```yaml
# Production stack, copied to /opt/crochet-back by the pipeline and started by deploy.sh.
services:
  app:
    image: ${DOCKERHUB_IMAGE:?set in release.env}:${IMAGE_TAG:?set in release.env}
    restart: unless-stopped
    env_file: .env
    # lets DB_URL reach a Postgres running on the VPS itself as host.docker.internal
    extra_hosts:
      - host.docker.internal:host-gateway
    # no published port: only Caddy reaches the app, so forwarded headers cannot be spoofed

  caddy:
    image: caddy:2
    restart: unless-stopped
    depends_on:
      - app
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    environment:
      DOMAIN: ${DOMAIN:?set in .env}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config

volumes:
  caddy_data:
  caddy_config:
```

`deploy/Caddyfile`:

```
# HTTPS certificate from Let's Encrypt for $DOMAIN, renewed automatically. Caddy sends
# X-Forwarded-For and X-Forwarded-Proto, which the app trusts from the Docker network.
{$DOMAIN} {
	reverse_proxy app:8080
}
```

`deploy/deploy.sh`:

```sh
#!/bin/sh
# Pulls and (re)starts the release named in release.env, with the settings in .env.
# Also used by hand: restart, or roll back by putting an older IMAGE_TAG in release.env.
set -eu
cd "$(dirname "$0")"
compose() {
    docker compose --env-file .env --env-file release.env "$@"
}
compose pull
compose up -d --remove-orphans
docker image prune -f
```

Append to `.gitignore`:

```
### Deployment ###
deploy/.env
deploy/release.env
```

- [ ] **Step 2: Validate the compose file**

Create throwaway env files in the scratchpad (never in the repo) and render the config:

```bash
S="$LOCALAPPDATA/Temp/claude-deploy-check"; mkdir -p "$S"
printf 'DB_URL=jdbc:postgresql://host.docker.internal:55432/crochet\nDB_USERNAME=crochet\nDB_PASSWORD=crochet\nDOMAIN=localhost\nCROCHET_CORS_ALLOWEDORIGINS=https://localhost\n' > "$S/.env"
printf 'DOCKERHUB_IMAGE=crochet-back\nIMAGE_TAG=local\n' > "$S/release.env"
cp deploy/compose.yaml deploy/Caddyfile deploy/deploy.sh "$S/"
(cd "$S" && docker compose --env-file .env --env-file release.env config --quiet && echo valid)
```

Expected: `valid`. Then render without `release.env` and expect the `set in release.env` error (proves the guard):
`(cd "$S" && docker compose --env-file .env config --quiet)` → error mentioning `set in release.env`.

- [ ] **Step 3: Run the stack locally behind Caddy with production cookie settings**

The image is local, so start without `pull`:

```bash
(cd "$S" && docker compose --env-file .env --env-file release.env up -d)
CURL_OPTS=-k bash ci/smoke-test.sh https://localhost
```

Expected: `Smoke test passed against https://localhost`. This runs with `SESSION_COOKIE_SECURE` unset (default `true`), so it proves the `Secure` cookies work over HTTPS and that the app sees `X-Forwarded-Proto` through Caddy. If ports 80/443 are busy on the machine, stop what holds them for the check; do not change the committed compose file.

- [ ] **Step 4: Check the app port is not published, then clean up**

Run: `(cd "$S" && docker compose --env-file .env --env-file release.env port app 8080)`
Expected: an error / empty output (no host port). Then `(cd "$S" && docker compose --env-file .env --env-file release.env down -v)` and `rm -rf "$S"`.

- [ ] **Step 5: Write `deploy/README.md`**

```markdown
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
```

- [ ] **Step 6: Commit**

```bash
git add deploy .gitignore
git commit -m "deploy: add the VPS stack with Caddy and its deploy script"
```

---

### Task 5: GitHub Actions workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `ci/smoke-test.sh` (Task 1), `Dockerfile` (Task 3), `deploy/*` and `deploy.sh` contract (Task 4).

- [ ] **Step 1: Write the workflow**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  pull_request:
    branches: [master]
  push:
    branches: [master]

permissions:
  contents: read

# one run per ref at a time: on master, runs queue so deploys keep the push order;
# on a PR, a new push cancels the previous run
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  test:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven
      - name: Run the test suite
        run: ./mvnw -B verify

  native:
    if: github.event_name == 'push'
    needs: test
    runs-on: ubuntu-24.04
    environment: production
    services:
      postgres:
        image: postgres:17
        env:
          POSTGRES_DB: crochet
          POSTGRES_USER: crochet
          POSTGRES_PASSWORD: crochet
        ports:
          - 5432:5432
        options: >-
          --health-cmd "pg_isready -U crochet"
          --health-interval 5s
          --health-timeout 5s
          --health-retries 10
    steps:
      - uses: actions/checkout@v4
      - uses: graalvm/setup-graalvm@v1
        with:
          java-version: '25'
          distribution: graalvm-community
          cache: maven
          github-token: ${{ secrets.GITHUB_TOKEN }}
      - name: Compile the native binary
        run: ./mvnw -B -Pnative -DskipTests native:compile
      - name: Smoke-test the native binary
        env:
          DB_URL: jdbc:postgresql://localhost:5432/crochet
          DB_USERNAME: crochet
          DB_PASSWORD: crochet
          SESSION_COOKIE_SECURE: 'false'
        run: |
          ./target/crochet_back > native.log 2>&1 &
          bash ci/smoke-test.sh http://localhost:8080
      - name: Show the native binary log
        if: failure()
        run: cat native.log
      - name: Compute the image tag
        id: tag
        run: echo "sha=sha-${GITHUB_SHA::7}" >> "$GITHUB_OUTPUT"
      - uses: docker/setup-buildx-action@v3
      - uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}
      - name: Build and push the image
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: |
            ${{ secrets.DOCKERHUB_USERNAME }}/crochet-back:latest
            ${{ secrets.DOCKERHUB_USERNAME }}/crochet-back:${{ steps.tag.outputs.sha }}

  deploy:
    needs: native
    runs-on: ubuntu-24.04
    environment: production
    concurrency:
      group: production
      cancel-in-progress: false
    env:
      TARGET: ${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}
    steps:
      - uses: actions/checkout@v4
      - name: Configure SSH with the pinned host key
        env:
          SSH_KEY: ${{ secrets.VPS_SSH_KEY }}
          KNOWN_HOSTS: ${{ secrets.VPS_KNOWN_HOSTS }}
        run: |
          install -m 700 -d ~/.ssh
          printf '%s\n' "$SSH_KEY" > ~/.ssh/deploy_key
          chmod 600 ~/.ssh/deploy_key
          printf '%s\n' "$KNOWN_HOSTS" > ~/.ssh/known_hosts
          printf 'Host *\n  IdentityFile ~/.ssh/deploy_key\n  StrictHostKeyChecking yes\n' > ~/.ssh/config
      - name: Write the release file
        env:
          DOCKERHUB_USERNAME: ${{ secrets.DOCKERHUB_USERNAME }}
        run: |
          printf 'DOCKERHUB_IMAGE=%s/crochet-back\nIMAGE_TAG=sha-%s\n' \
            "$DOCKERHUB_USERNAME" "${GITHUB_SHA::7}" > deploy/release.env
      - name: Copy the stack to the VPS
        run: |
          scp deploy/compose.yaml deploy/Caddyfile deploy/deploy.sh deploy/release.env \
            "$TARGET:/opt/crochet-back/"
      - name: Restart the stack
        run: ssh "$TARGET" 'sh /opt/crochet-back/deploy.sh'
      - name: Check the deployed API over HTTPS
        env:
          DOMAIN: ${{ vars.DEPLOY_DOMAIN }}
        run: |
          for _ in $(seq 30); do
            status="$(curl -s -o /dev/null -w '%{http_code}' "https://$DOMAIN/api/auth/csrf" || true)"
            if [ "$status" = 204 ]; then
              echo "API answers on https://$DOMAIN"
              exit 0
            fi
            sleep 2
          done
          echo "API did not answer 204 on https://$DOMAIN (last status: $status)" >&2
          exit 1
```

- [ ] **Step 2: Lint it**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)":/repo -w /repo rhysd/actionlint:1.7.7 -color`
Expected: no output, exit 0. Fix every reported issue (shellcheck findings in `run:` blocks included).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: test, build the native image and deploy it on push to master"
```

---

## Self-review notes

- Spec §2 (jobs, smoke test, tags, concurrency, availability check) → Tasks 1, 5. §3 (Dockerfile, .dockerignore, NativeHints, local reproduction) → Tasks 2, 3. §4 (compose, Caddyfile, deploy.sh, .env, release.env, README, secrets) → Tasks 4, 5. §5 (verification before push, failures, migration rule) → Tasks 1, 3, 4, 5 and the README.
- The workflow cannot run before a remote exists; the verification that is possible locally (native binary + image + stack behind Caddy + actionlint) is in Tasks 3-5.
