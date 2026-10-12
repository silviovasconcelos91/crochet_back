# Comptes et avancement — Design (backend)

Date : 2026-10-12
Repo : `crochet_back` (Spring Boot 4.1.1, Java 21)
S'appuie sur : `docs/superpowers/specs/2026-10-12-crochet-patterns-design.md` (API patterns v1)

## 1. Objectif

Permettre de créer un compte et de s'y connecter. Chaque compte possède ses propres patterns
(privés) et peut démarrer, faire avancer, terminer ou abandonner un pattern. L'avancement mémorise
l'étape et le rang courants (pas la maille) pour reprendre plus tard.

### Ce qui est demandé

- Création de compte, connexion, déconnexion.
- Patterns privés : chaque compte ne voit et ne modifie que les siens.
- Cycle de vie de l'avancement : non commencé → en cours → terminé ; abandon possible (retour à
  non commencé) ; un pattern terminé peut être rouvert.
- Avancement = étape courante + rang courant.
- Changement de mot de passe, suppression du compte.

### Hypothèses

- Un seul type de compte, pas de rôle.
- Le front tourne sur `localhost:5173`, le back sur `localhost:8080` (même site).

### Hors périmètre v1

Mot de passe oublié, vérification d'e-mail (pas de SMTP), rôles/admin, « se souvenir de moi »,
OAuth, déploiement cross-site (`SameSite=None`), **limitation des tentatives de login** (risque de
brute force accepté en local, à traiter avant toute mise en ligne).

## 2. Modèle de données

### Migrations V2 à V5

Découpées pour que chaque étape d'implémentation garde une suite verte : V2 `account`, V3 tables
Spring Session, V4 `pattern.owner_id`, V5 `pattern_progress`.

```
account
  id             bigserial PK
  email          varchar(254) NOT NULL UNIQUE    -- toujours stocké en minuscules
  password_hash  varchar(100) NOT NULL           -- BCrypt
  created_at     timestamptz  NOT NULL

pattern (modifiée)
  owner_id       bigint NOT NULL FK → account(id) ON DELETE CASCADE
  INDEX (owner_id)

pattern_progress
  pattern_id     bigint PK FK → pattern(id) ON DELETE CASCADE
  status         varchar(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'FINISHED'))
  step_position  int NOT NULL CHECK (step_position >= 0)
  row_position   int NOT NULL CHECK (row_position >= 0)
  started_at     timestamptz NOT NULL
  updated_at     timestamptz NOT NULL
  finished_at    timestamptz
  CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL))
```

La migration V4 **supprime les patterns existants** (données de dev sans propriétaire) avant
d'ajouter `owner_id NOT NULL`. `account` porte aussi `CHECK (email = lower(email))`.

### Sessions (V3)

Tables Spring Session JDBC (`spring_session`, `spring_session_attributes`), reprises du script
PostgreSQL officiel de Spring Session. `spring.session.jdbc.initialize-schema: never` : Flyway
reste seul maître du schéma.

### Décisions

- **Email = identifiant de connexion**, normalisé (`trim` + minuscules) à l'inscription et au login.
- L'avancement n'a pas de colonne compte : son propriétaire est celui du pattern.
- Suppression d'un compte : cascade base → patterns → étapes, rangs, avancement.
- **Positions en base 0**, alignées sur l'ordre des tableaux `steps[]` / `rows[]` du JSON (même
  convention que `step.position` / `pattern_row.position`).
- Timestamps tronqués à la microseconde côté application (comme `Pattern`).

## 3. API REST

Toutes les erreurs : `ProblemDetail` (RFC 9457), y compris 401 et 403.

### Authentification — `/api/auth` (sans connexion)

| Méthode | URL                  | Corps               | Succès                              | Erreurs   |
|---------|----------------------|---------------------|-------------------------------------|-----------|
| GET     | `/api/auth/csrf`     | —                   | 204, pose le cookie `XSRF-TOKEN`    | —         |
| POST    | `/api/auth/register` | `{email, password}` | 201 `Account`, session ouverte      | 400, 409  |
| POST    | `/api/auth/login`    | `{email, password}` | 200 `Account`, session ouverte      | 400, 401  |
| POST    | `/api/auth/logout`   | —                   | 204, session invalidée              | —         |

Les POST exigent le token CSRF (y compris register et login).

### Compte — `/api/me` (connexion requise)

| Méthode | URL                | Corps                             | Succès | Erreurs        |
|---------|--------------------|-----------------------------------|--------|----------------|
| GET     | `/api/me`          | —                                 | 200 `Account` | 401     |
| PUT     | `/api/me/password` | `{currentPassword, newPassword}`  | 204    | 400, 401       |
| DELETE  | `/api/me`          | `{password}`                      | 204    | 400, 401       |

`Account` = `{ "id": 1, "email": "moi@exemple.fr", "createdAt": "..." }`

### Patterns (connexion requise)

Endpoints inchangés (`GET/POST /api/patterns`, `GET/PUT/DELETE /api/patterns/{id}`), avec :

- 401 sans session.
- Résultats limités aux patterns du compte connecté ; un pattern d'un autre compte (ou inexistant)
  → **404**, jamais 403.
- `POST` rattache le pattern au compte connecté.
- `PatternSummary` et `PatternResponse` gagnent `progress` : objet `Progress` ou `null` si non
  commencé.
- Après un `PUT` de pattern, si l'avancement pointe hors de la nouvelle structure, il est recadré :
  `stepIndex = min(stepIndex, nbÉtapes - 1)`, puis `rowIndex = min(rowIndex, nbRangs(étape) - 1)`.
  Le statut et `startedAt` sont conservés. Insérer une étape avant l'étape courante décale
  l'avancement d'une étape (limite acceptée).

### Avancement (connexion requise)

| Méthode | URL                           | Corps                             | Succès          | Erreurs  |
|---------|-------------------------------|-----------------------------------|-----------------|----------|
| PUT     | `/api/patterns/{id}/progress` | `{status, stepIndex, rowIndex}`   | 200 `Progress`  | 400, 404 |
| DELETE  | `/api/patterns/{id}/progress` | —                                 | 204             | 404      |

`Progress` = `{ "status": "IN_PROGRESS", "stepIndex": 0, "rowIndex": 3, "startedAt": "...",
"updatedAt": "...", "finishedAt": null }`

Sémantique :

- `PUT` crée l'avancement s'il n'existe pas (démarrer = `{IN_PROGRESS, 0, 0}`), sinon le met à jour.
- `startedAt` : fixé à la création, jamais modifié ensuite. `updatedAt` : à chaque PUT.
- `status: FINISHED` → `finishedAt` = maintenant (s'il ne l'était pas déjà). `status: IN_PROGRESS`
  sur un avancement terminé → rouvert, `finishedAt = null`.
- `DELETE` (abandon) sur un pattern non commencé → 404.
- Validation (400, champ nommé dans `errors`) : `status` requis (`IN_PROGRESS` | `FINISHED`) ;
  `stepIndex`, `rowIndex` requis, ≥ 0 ; `stepIndex` < nombre d'étapes (`stepIndex`) ;
  `rowIndex` < nombre de rangs de cette étape (`rowIndex`).

### Règles de compte

- `email` : requis, format e-mail, ≤ 254 caractères.
- `password` / `newPassword` : ≥ 8 caractères et ≤ 72 octets en UTF-8 (limite BCrypt).
- Inscription avec un email déjà utilisé (casse ignorée) → 409.
- Login : même 401 et même message que l'email soit inconnu ou le mot de passe faux. L'id de
  session change à la connexion.
- Changement de mot de passe : `currentPassword` faux → 400 avec `errors[].field =
  "currentPassword"`. En cas de succès, toutes les **autres** sessions du compte sont invalidées ;
  la session courante reste valide.
- Suppression : `password` faux → 400 avec `errors[].field = "password"`. En cas de succès, la
  session est invalidée.

## 4. Sécurité

- Spring Security 7. `formLogin` et `httpBasic` désactivés.
- `/api/auth/**` : accès libre. Tout autre `/api/**` : authentifié.
- Login programmatique : `AuthenticationManager` (DAO + `BCryptPasswordEncoder`), puis sauvegarde
  explicite du `SecurityContext` dans la session ; changement d'id de session (fixation).
- Logout : invalidation de la session, effacement du contexte.
- **CSRF** mode SPA : cookie `XSRF-TOKEN` (lisible par JS), en-tête `X-XSRF-TOKEN`. Requis sur
  toutes les méthodes non sûres.
- **CORS** appliqué dans la chaîne Spring Security (bean `CorsConfigurationSource`), sur `/api/**` :
  origines de `crochet.cors.allowed-origins`, méthodes GET/POST/PUT/DELETE, en-têtes autorisés
  `Content-Type` et `X-XSRF-TOKEN`, `Location` exposé, `allowCredentials(true)`.
- **Cookie de session** : HttpOnly, `SameSite=Lax`, `Secure` piloté par
  `server.servlet.session.cookie.secure` (faux par défaut en dev).
- **Expiration d'inactivité** : 7 jours (`spring.session.timeout: 7d`, la propriété que lit Spring Session).
- **Spring Session JDBC** : sessions dans Postgres ; l'index par nom de principal sert à invalider
  les autres sessions lors d'un changement de mot de passe.
- 401 (non authentifié) et 403 (CSRF manquant/invalide) renvoyés en `ProblemDetail`.

## 5. Architecture du code

```
account/
  Account.java, AccountRepository.java       entité + findByEmail
  AccountService.java                        inscription, changement de mot de passe, suppression
  AuthController.java                        /api/auth
  MeController.java                          /api/me
  AccountPrincipal.java                      principal Spring Security (id, email, hash)
  AccountUserDetailsService.java             charge par email normalisé
  EmailAlreadyUsedException.java
  dto/  RegisterRequest, LoginRequest, ChangePasswordRequest, DeleteAccountRequest,
        AccountResponse, BcryptPassword (contrainte)
pattern/   (existant, modifié)
  Pattern.java           + ownerId (Long), + progress (@OneToOne, cascade ALL, orphanRemoval)
  PatternProgress.java   entité de l'agrégat Pattern
  PatternRepository      findByIdAndOwnerId, findAllSummaries(ownerId) avec LEFT JOIN progress
  PatternService         ownerId partout ; replace() recadre l'avancement
  ProgressController / ProgressService
  dto/  ProgressRequest, ProgressResponse ; progress ajouté aux DTO pattern
config/
  SecurityConfig.java    SecurityFilterChain, PasswordEncoder, AuthenticationManager
  CorsConfig.java        bean CorsConfigurationSource (remplace le WebMvcConfigurer)
  ApiExceptionHandler    + 409 ; points d'entrée 401/403 en ProblemDetail
```

- L'avancement appartient au package `pattern` (agrégat) : un package séparé créerait un cycle.
- `Pattern.ownerId` est un `Long` sans relation JPA vers `Account` : `pattern` ne dépend pas de
  `account` ; l'intégrité est assurée par la FK.
- Les controllers obtiennent l'id du compte via `@AuthenticationPrincipal AccountPrincipal`.

## 6. Tests

Approche TDD, Testcontainers PostgreSQL, MockMvc.

- **Auth (flux réel, cookies)** : inscription → `/me` ; email pris (autre casse) → 409 ; mauvais
  login → 401 identique pour email inconnu ; POST sans CSRF → 403 ; logout → `/me` 401 ; session
  persistée dans `spring_session` ; changement de mot de passe : ancien mot de passe refusé
  ensuite, autre session → 401, session courante OK ; mauvais `currentPassword` → 400 ; suppression
  → patterns, étapes, rangs, avancements supprimés, session invalidée ; mot de passe > 72 octets
  UTF-8 → 400 ; < 8 caractères → 400.
- **Isolation** : le compte B obtient 404 sur GET/PUT/DELETE et PUT/DELETE progress d'un pattern de
  A ; la liste de B n'inclut pas les patterns de A ; sans session → 401.
- **Avancement** : démarrer, avancer, terminer (`finishedAt` renseigné), rouvrir (`finishedAt`
  null, `startedAt` inchangé), abandonner, abandon d'un non commencé → 404, positions hors bornes →
  400 sur le bon champ, recadrage après PUT de pattern, `progress` dans le résumé et le détail,
  `null` si non commencé.
- **Tests existants** : contenu inchangé ; chaque requête porte un compte réel (helper de test) et
  le token CSRF. Ajout de `spring-security-test`.
