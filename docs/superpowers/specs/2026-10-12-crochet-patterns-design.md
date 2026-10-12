# Crochet Patterns — Design (backend)

Date : 2026-10-12
Repo : `crochet_back` (Spring Boot 4.1.1, Java 21)

## 1. Objectif

Stocker et exposer une collection personnelle de patterns de crochet via une API REST,
consommée par un front séparé (autre projet). Le front affiche la liste des patterns, puis, pour un
pattern, la liste ordonnée des étapes et des rangs à réaliser.

### Ce qui est demandé

- Stockage PostgreSQL.
- Un pattern = un titre + 1..n étapes.
- Chaque étape contient une liste ordonnée de rangs.
- Chaque rang est une suite de crochetage saisie en **texte libre** (ex : `3ms, [1ms, 4aug]x6 (34)`).
- CRUD complet via l'API.
- Infos du pattern : description, taille de crochet (mm), fil, URL source, URL image.

### Hypothèses

- Usage mono-utilisateur, pas d'authentification.
- Petit volume de données : pas de pagination.

### Hors périmètre v1

Authentification, pagination, recherche, upload d'images (URL seulement), suivi de progression,
glossaire/validation des abréviations de mailles, calcul du nombre de mailles, build natif GraalVM.

## 2. Modèle de données

Schéma géré exclusivement par Flyway (`src/main/resources/db/migration`).

```
pattern
  id            bigserial PK
  title         varchar(200) NOT NULL
  description   text
  hook_size_mm  numeric(4,2)
  yarn          varchar(200)
  source_url    varchar(500)
  image_url     varchar(500)
  created_at    timestamptz NOT NULL
  updated_at    timestamptz NOT NULL

step
  id          bigserial PK
  pattern_id  bigint NOT NULL FK → pattern(id) ON DELETE CASCADE
  position    int NOT NULL
  title       varchar(200) NOT NULL
  UNIQUE (pattern_id, position)

pattern_row
  id          bigserial PK
  step_id     bigint NOT NULL FK → step(id) ON DELETE CASCADE
  position    int NOT NULL
  number      varchar(20) NOT NULL
  value       text NOT NULL
  UNIQUE (step_id, position)
```

Décisions :

- **`pattern_row`** plutôt que `row` (mot-clé SQL).
- **`number` en texte** : les patterns utilisent des plages de rangs (`"4-10"`).
- **`position` séparée de `number`** : l'ordre d'affichage vient de l'ordre du tableau JSON, pas du
  contenu de `number`. Positions indexées à partir de 0.
- `created_at` / `updated_at` sont renseignés par l'application (`@CreationTimestamp` /
  `@UpdateTimestamp`).

## 3. API REST

Préfixe `/api`. JSON. Propriétés en camelCase.

| Méthode | URL                  | Succès                  | Erreurs   |
|---------|----------------------|-------------------------|-----------|
| GET     | `/api/patterns`      | 200 `[PatternSummary]`  | —         |
| GET     | `/api/patterns/{id}` | 200 `PatternResponse`   | 404       |
| POST    | `/api/patterns`      | 201 + `Location` + body | 400       |
| PUT     | `/api/patterns/{id}` | 200 `PatternResponse`   | 400, 404  |
| DELETE  | `/api/patterns/{id}` | 204                     | 404       |

La liste `GET /api/patterns` est triée par `title` (ordre alphabétique, insensible à la casse).

### PatternSummary

```json
{ "id": 1, "title": "Amigurumi Chat", "description": "...", "hookSizeMm": 3.5,
  "yarn": "Coton 8/4", "imageUrl": "https://...", "stepCount": 3 }
```

### PatternResponse

```json
{
  "id": 1,
  "title": "Amigurumi Chat",
  "description": "...",
  "hookSizeMm": 3.5,
  "yarn": "Coton 8/4",
  "sourceUrl": "https://...",
  "imageUrl": "https://...",
  "createdAt": "2026-10-12T10:00:00Z",
  "updatedAt": "2026-10-12T10:00:00Z",
  "steps": [
    {
      "title": "Tête",
      "rows": [
        { "number": "1", "value": "6ms dans cercle magique (6)" },
        { "number": "2", "value": "6aug (12)" },
        { "number": "3", "value": "3ms, [1ms, 4aug]x6 (34)" },
        { "number": "4-10", "value": "34ms (34)" }
      ]
    }
  ]
}
```

### PatternRequest (POST et PUT)

Même forme que `PatternResponse`, sans `id`, `createdAt`, `updatedAt`. Les étapes et rangs sont
renvoyés dans l'ordre exact où ils ont été envoyés.

Règles de validation (→ 400) :

- `title` : non vide, ≤ 200 caractères.
- `description` : optionnelle.
- `hookSizeMm` : optionnel, > 0, au plus 2 chiffres avant la virgule et 2 après (`numeric(4,2)`).
- `yarn` : optionnel, ≤ 200 caractères.
- `sourceUrl`, `imageUrl` : optionnels, ≤ 500 caractères.
- `steps` : au moins 1 élément.
  - `title` : non vide, ≤ 200 caractères.
  - `rows` : au moins 1 élément.
    - `number` : non vide, ≤ 20 caractères.
    - `value` : non vide.

### Sémantique

- **PUT remplace entièrement** le pattern : champs simples et arbre des étapes/rangs. Pas de PATCH.
- **DELETE** supprime le pattern et ses étapes/rangs (cascade).

### Erreurs

Format `ProblemDetail` (RFC 9457), `Content-Type: application/problem+json`.

- 400 validation : `detail` générique + propriété `errors` = liste `{ "field": "steps[0].rows[1].value", "message": "..." }`.
- 400 JSON illisible.
- 404 : pattern introuvable.

### CORS

Origines autorisées configurables via `crochet.cors.allowed-origins` (liste) dans
`application.yaml`, appliquées à `/api/**`. Valeur par défaut dev : `http://localhost:5173`.

## 4. Architecture du code

Organisation par feature, package racine `vasconcelos.silvio.crochet_back`.

```
pattern/
  Pattern.java, Step.java, PatternRow.java   entités JPA
  PatternRepository.java                     Spring Data JPA
  PatternService.java                        logique métier + transactions
  PatternController.java                     REST /api/patterns
  PatternMapper.java                         entité ↔ DTO (manuel)
  PatternNotFoundException.java
  dto/
    PatternRequest, StepRequest, RowRequest            records + Bean Validation
    PatternResponse, StepResponse, RowResponse,
    PatternSummary                                     records
config/
  CorsConfig.java                            WebMvcConfigurer
  CorsProperties.java                        @ConfigurationProperties("crochet.cors")
  ApiExceptionHandler.java                   @RestControllerAdvice → ProblemDetail
```

Responsabilités :

- **Controller** : HTTP uniquement (codes, `@Valid`, `Location`). Aucune logique.
- **Service** : transactions, chargement, création, remplacement, suppression ; lève
  `PatternNotFoundException`.
- **Mapper** : conversions pures, sans accès base ; assigne `position` d'après l'index.
- **Repository** : `JpaRepository<Pattern, Long>` + requête JPQL de projection pour la liste
  résumée (`stepCount` calculé en SQL, tri par `lower(title)`).

### Persistance

- `Pattern.steps` et `Step.rows` : `List`, `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true)`,
  `@OrderBy("position")`, chargement lazy.
- **Remplacement (PUT)** : Hibernate exécute les INSERT avant les DELETE d'orphelins au flush, ce
  qui violerait `UNIQUE(pattern_id, position)`. Procédure : `pattern.getSteps().clear()`, puis
  `flush()`, puis ajout des nouvelles étapes.
- Lecture du détail : service `@Transactional(readOnly = true)`, mapping vers DTO dans la
  transaction ; `hibernate.default_batch_fetch_size` configuré pour éviter le N+1. Pas de fetch
  join sur les deux collections imbriquées (`MultipleBagFetchException`).
- `spring.jpa.hibernate.ddl-auto: validate`, `spring.jpa.open-in-view: false`.

## 5. Configuration et infrastructure

- Dépendances ajoutées : data-jpa, validation, driver postgresql, flyway + module postgresql,
  testcontainers postgresql + support Spring Boot testcontainers. Noms d'artefacts exacts vérifiés
  pour Spring Boot 4 lors du plan.
- `docker-compose.yml` : PostgreSQL 17, base `crochet`, port 5432.
- `application.yaml` : `spring.datasource.url/username/password` via variables d'environnement
  (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`) avec valeurs par défaut pointant sur le compose local.
- Le plugin `native-maven-plugin` reste dans le pom mais le build natif n'est pas une cible v1.

## 6. Tests

Approche TDD.

- **Intégration** (`@SpringBootTest` + Testcontainers PostgreSQL via `@ServiceConnection` + MockMvc) :
  - POST crée et renvoie 201 + `Location` ; GET détail renvoie le même contenu.
  - L'ordre des étapes et des rangs est conservé.
  - GET liste : résumés triés par titre, `stepCount` correct.
  - PUT remplace champs et arbre (y compris avec moins / plus d'étapes qu'avant).
  - DELETE renvoie 204 puis GET → 404 ; étapes et rangs supprimés en base.
  - 404 sur GET / PUT / DELETE d'un id inexistant.
  - 400 sur chaque règle de validation, avec chemin de champ dans `errors`.
  - 400 sur JSON illisible.
- **Unitaire** : `PatternMapper` (positions, conversions dans les deux sens).
- Prérequis : Docker démarré pour exécuter les tests.
