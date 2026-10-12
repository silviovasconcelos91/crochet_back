# Patterns publics et pseudos — Design (backend)

Date : 2026-10-12
Repo : `crochet_back` (Spring Boot 4.1.1, Java 21)
S'appuie sur : `docs/superpowers/specs/2026-10-12-accounts-progress-design.md` (comptes et
avancement)

## 1. Objectif

À la création (et à l'édition) d'un pattern, son propriétaire choisit s'il reste **privé** (visible
de lui seul) ou devient **public**. Un pattern public est consultable par tous les comptes connectés,
qui peuvent le **suivre avec leur propre avancement**. Le catalogue des patterns publics affiche
l'auteur par un **pseudo**, qui devient aussi l'identifiant de connexion.

### Ce qui est demandé

- Visibilité `PRIVATE` / `PUBLIC` choisie à la création, modifiable ensuite.
- Catalogue séparé des patterns publics ; « mes patterns » reste la liste de mes patterns.
- Avancement propre à chaque couple (compte, pattern) : deux comptes avancent sur le même pattern
  en même temps sans conflit et sans voir l'avancement de l'autre.
- Repasser un pattern en privé coupe l'accès des autres comptes et **supprime leur avancement**.
- Pseudo obligatoire, unique, modifiable ; **connexion par pseudo uniquement**.

### Hypothèses

- Seul le propriétaire modifie ou supprime un pattern (titre, étapes, rangs, visibilité).
- L'email reste obligatoire et unique à l'inscription (future réinitialisation de mot de passe),
  mais ne sert plus à se connecter.
- Aucun compte de production n'est à préserver.

### Hors périmètre

Pagination et recherche du catalogue (limite connue, à ajouter quand il grossira), copie d'un
pattern public dans son compte, accès sans connexion, lien de partage, modération.

## 2. Modèle de données

### Migration V7 — remise à zéro et pseudo

**Irréversible** : V7 vide toutes les données de compte. Patterns, étapes, rangs et avancements
partent en cascade ; les sessions sont vidées car leur `principal_name` est l'ancien email.

```
TRUNCATE account, pattern, spring_session RESTART IDENTITY CASCADE

account (modifiée)
  username       varchar(30) NOT NULL     -- tel que saisi (casse conservée, NFC)
  username_key   varchar(60) NOT NULL     -- forme normalisée (les minuscules peuvent allonger)
  CONSTRAINT uk_account_username_key UNIQUE (username_key)
```

### Migrations V8 (visibilité) et V9 (avancement par compte)

```
pattern (modifiée, V8)
  visibility     varchar(10) NOT NULL DEFAULT 'PRIVATE'
                 CHECK (visibility IN ('PRIVATE', 'PUBLIC'))
  INDEX (lower(title), id) WHERE visibility = 'PUBLIC'   -- catalogue

pattern_progress (recréée, vide, V9)
  pattern_id     bigint NOT NULL FK → pattern(id) ON DELETE CASCADE
  account_id     bigint NOT NULL FK → account(id) ON DELETE CASCADE
  PRIMARY KEY (pattern_id, account_id)
  INDEX (account_id)
  status, step_position, row_position, started_at, updated_at, finished_at
                 -- colonnes et CHECK inchangés
```

Trois migrations pour garder une suite verte à chaque étape d'implémentation : V7 avec le pseudo,
V8 avec la visibilité, V9 avec l'avancement par compte.

### Pseudo

- Saisie : `trim`, puis normalisation Unicode **NFC** (un « é » composé et décomposé sont le même
  pseudo). C'est la valeur stockée dans `username` et affichée.
- Format : 3 à 30 caractères parmi les lettres (accents compris), chiffres, `_`, `-`, `.`
  (`^[\p{L}\p{N}_.-]{3,30}$`), donc ni espace ni `@`.
- `username_key` = `username` en minuscules (`Locale.ROOT`). Calculée **en Java par une seule
  fonction** (`Usernames.key`), utilisée pour l'unicité, la recherche au login et la limite de
  tentatives : les trois comparent exactement la même chose.

### Décisions

- **Le principal de session est l'id du compte**, plus l'email : `AccountPrincipal.getUsername()`
  renvoie l'id en texte, qui devient le `principal_name` de Spring Session. Un changement de pseudo
  ne casse donc ni les sessions ouvertes ni la fermeture des « autres sessions » au changement de
  mot de passe.
- `Pattern` perd son `@OneToOne progress` : l'avancement n'est plus une partie de l'agrégat
  pattern mais une entité rattachée à (pattern, compte), lue pour le compte connecté.
- `pattern` ne dépend toujours pas de `account` côté Java : le pseudo de l'auteur est obtenu par
  une jointure SQL dans les requêtes du repository, sans relation JPA.

## 3. API REST

### Authentification et compte

| Méthode et route | Corps | Réponse |
|---|---|---|
| `POST /api/auth/register` | `{ "username", "email", "password" }` | `201` + `Account`, connecté. `409` si le pseudo **ou** l'email est pris |
| `POST /api/auth/login` | `{ "username", "password" }` | `200` + `Account`. `401` si identifiants invalides |
| `GET /api/me` | — | `200` + `Account` |
| `PUT /api/me/username` | `{ "username" }` | `200` + `Account`. `400` format, `409` pseudo pris |

- `Account` = `{ "id", "username", "email" }`.
- Le `409` indique le champ en conflit : `errors: [{ "field": "username" | "email", "message":
  "is already used" }]`. Si les deux sont pris, les deux erreurs sont renvoyées.
- Login : pseudo comparé par `username_key` ; un email n'est plus accepté (il échoue comme un pseudo
  inconnu, `401`). Mauvais pseudo et mauvais mot de passe restent indiscernables.
- Choisir comme nouveau pseudo son pseudo actuel (même dans une autre casse) est accepté : `200`,
  la casse saisie est enregistrée.
- **Limite de tentatives** : la clé « email » devient la `username_key`. Seuils inchangés.
  `PUT /api/me/password` et `DELETE /api/me` restent limités comme le login.

### Patterns

| Méthode et route | Propriétaire | Autre compte, public | Autre compte, privé |
|---|---|---|---|
| `GET /api/patterns` | mes patterns | — | — |
| `GET /api/patterns/public` | tous les publics, miens compris | idem | — |
| `GET /api/patterns/{id}` | `200` | `200` | `404` |
| `POST /api/patterns` | `201` | — | — |
| `PUT /api/patterns/{id}` | `200` | `403` | `404` |
| `DELETE /api/patterns/{id}` | `204` | `403` | `404` |
| `PUT /api/patterns/{id}/progress` | `200` | `200` | `404` |
| `DELETE /api/patterns/{id}/progress` | `204` | `204` | `404` |

- Un pattern privé d'un autre compte reste indiscernable d'un pattern inexistant (`404`). Un
  pattern public d'un autre compte est visible : le modifier donne une `403`.
- `GET /api/patterns/public` : trié par titre sans tenir compte de la casse, puis par id. Pas de
  pagination.
- L'avancement (`progress`) d'un `Pattern` ou `PatternSummary` est toujours **celui du compte
  connecté**, `null` s'il n'a pas commencé.

### Champs

- `PatternInput` : `visibility` facultatif, `"PRIVATE"` ou `"PUBLIC"`. Omis → `"PRIVATE"`, y compris
  sur un `PUT` (cohérent avec « un champ omis est remis à sa valeur par défaut »). Valeur inconnue
  → `400`.
- `Pattern` et `PatternSummary` ajoutent :
  - `visibility` ;
  - `author: { "username" }`, pseudo courant de l'auteur ;
  - `mine: boolean`, vrai si le compte connecté est le propriétaire.

### Règles d'écriture

- **Passage en privé** : un `PUT` qui fait passer `PUBLIC` → `PRIVATE` supprime, dans la même
  transaction, l'avancement de tous les comptes sauf le propriétaire.
- **Changement d'étapes** : le `PUT` recadre l'avancement de **tous** les comptes sur les nouvelles
  étapes (même règle qu'aujourd'hui pour chacun).
- **Suppression** du pattern ou d'un compte : cascade en base.

## 4. Concurrence

- `PUT` et `DELETE` d'un pattern : verrou **exclusif** sur la ligne `pattern` (`FOR UPDATE`), comme
  aujourd'hui.
- Écriture d'avancement (`PUT`/`DELETE …/progress`) : verrou **partagé** sur la ligne `pattern`
  (`FOR SHARE`), puis écriture de la ligne `(pattern, compte)`.
  - Deux comptes qui avancent sur le même pattern ne s'attendent pas.
  - Une écriture d'avancement et une modification du pattern s'enchaînent : l'avancement est
    toujours recadré sur les étapes à jour, et une écriture qui arrive après un passage en privé
    relit la visibilité sous verrou et reçoit une `404`.
- Premier `PUT` de progress concurrent pour le même compte (double clic) : l'insertion de la
  seconde requête viole la clé primaire ; elle est rejouée comme une mise à jour (ou renvoie la
  ligne existante), jamais une `500`.

## 5. Architecture du code

```
account/
  Usernames.java                 normalisation NFC, format, username_key
  Account.java                   + username, usernameKey
  AccountRepository              findByUsernameKey, existsByUsernameKey
  AccountService                 register(username, email, password), changeUsername
  AccountPrincipal               id comme getUsername(), + username pour l'affichage
  AccountUserDetailsService      charge par username_key
  AlreadyUsedException           remplace EmailAlreadyUsedException ; porte les champs en conflit
  dto/  RegisterRequest (+ username), LoginRequest (username), ChangeUsernameRequest,
        AccountResponse (+ username)
pattern/
  Visibility.java                enum PRIVATE, PUBLIC
  Pattern.java                   + visibility ; − progress
  PatternProgress.java           clé composite (patternId, accountId)
  PatternProgressRepository      find/lock par (pattern, compte), deleteOthers, findAllByPattern
  PatternRepository              findReadable(id, accountId), findForShare, findAllSummaries(owner),
                                 findPublicSummaries(accountId) — progress et pseudo par jointure
  PatternService                 accès lecture (propriétaire ou public), écriture (propriétaire),
                                 passage en privé, recadrage de tous les avancements
  PatternAccessDeniedException   → 403
  ProgressService                verrou partagé, avancement du compte connecté
  dto/  PatternRequest (+ visibility), PatternResponse / PatternSummary (+ visibility, author, mine)
config/
  ApiExceptionHandler            + 403 sur PatternAccessDeniedException, 409 multi-champs
```

## 6. Tests

TDD, Testcontainers PostgreSQL, MockMvc. Les classes `@Transactional` vident la base en
`@BeforeEach` comme les autres.

- **Pseudo et auth** : inscription avec pseudo ; pseudo pris dans une autre casse → 409 `username` ;
  email pris → 409 `email` ; les deux → deux erreurs ; format invalide (2 car., 31 car., espace,
  `@`) → 400 ; « é » composé et décomposé = même pseudo ; login par pseudo dans une autre casse →
  200 ; login par email → 401 ; changement de pseudo → `/me` et l'auteur du catalogue suivent, la
  session reste ouverte ; pseudo pris → 409 ; changement de mot de passe ferme toujours les autres
  sessions (principal = id).
- **Limite de tentatives** : comptée par pseudo, casse différente = même compteur.
- **Visibilité et accès** : matrice complète de la section 3 ; défaut `PRIVATE` à la création et sur
  `PUT` sans le champ ; `visibility`, `author`, `mine` dans le détail et les listes.
- **Catalogue** : contient les publics de tous, miens compris ; jamais un privé ; tri par titre ;
  mon avancement par élément.
- **Avancement par compte** : A et B avancent sur le même pattern public, chacun voit le sien ;
  abandon de B n'affecte pas A ; passage en privé supprime l'avancement de B, garde celui de A, B
  reçoit 404 ; retour en public : B repart de zéro ; `PUT` d'étapes recadre A et B ; suppression du
  compte B supprime son avancement, pas le pattern.
- **Concurrence** : deux comptes écrivent leur avancement en parallèle sans blocage ; premier `PUT`
  de progress doublé → pas de 500.
- **Migrations** : `SchemaMigrationTests` couvre V7, V8 et V9 (contraintes, clés).
- **Doc** : `docs/front-api.md` mis à jour (pseudo, login, visibilité, catalogue, matrice d'accès,
  avancement par compte).
