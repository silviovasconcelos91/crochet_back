# Contexte : API backend « crochet_back » à consommer depuis le front

Tu développes le front d'une application de suivi de patterns de crochet. Le backend est une API REST Spring Boot, déjà terminée. Voici son fonctionnement exact : n'invente aucun endpoint ni aucun champ en dehors de ce qui suit.

## 1. Concepts métier

- **Compte** : un utilisateur s'inscrit avec un email et un mot de passe. Chaque compte ne voit et ne modifie **que ses propres patterns**.
- **Pattern** : un modèle de crochet. Il a un titre, des infos facultatives (description, taille de crochet en mm, laine, URL source, URL d'image) et **1 à n étapes**.
- **Étape (step)** : un titre et **1 à n rangs**, dans l'ordre.
- **Rang (row)** : deux textes libres.
  - `number` : le libellé du rang, par exemple `"1"`, `"2"` ou `"3-5"`.
  - `value` : les instructions, par exemple `"3ms, [1ms, 4aug]x6 (34)"`.
  - Le backend n'interprète **pas** ces textes.
- **Avancement (progress)** : où l'utilisateur en est sur un pattern, soit l'**étape courante** et le **rang courant**, pas la maille.
  - Pattern non commencé : `progress` vaut `null`.
  - Statuts : `IN_PROGRESS` (en cours) ou `FINISHED` (terminé).
  - On peut démarrer, avancer, terminer, rouvrir un pattern terminé, ou **abandonner**. L'abandon supprime l'avancement, et le pattern redevient non commencé.
  - Les positions sont **indexées à partir de 0** : `stepIndex` est l'index dans `steps`, `rowIndex` l'index dans `steps[stepIndex].rows`.

## 2. Authentification, session et CSRF (à respecter impérativement)

L'API utilise une **session par cookie** (cookie `SESSION`, HttpOnly, invisible en JavaScript), et non un JWT.

1. **Toujours** envoyer les cookies : `fetch(url, { credentials: 'include' })`, ou `withCredentials: true` avec axios.
2. **Protection CSRF** :
   - Au démarrage de l'app, appeler une fois `GET /api/auth/csrf`. La réponse est un 204 qui dépose un cookie `XSRF-TOKEN`, lisible en JS.
   - Pour **chaque** requête `POST`, `PUT` ou `DELETE`, y compris register, login et logout : lire la valeur **actuelle** du cookie `XSRF-TOKEN` et l'envoyer dans l'en-tête `X-XSRF-TOKEN`.
   - Le token change après le login, l'inscription et le logout, et le serveur renvoie lui-même le nouveau cookie. Il faut donc relire le cookie au moment de chaque requête, sans le mettre en cache. Il n'est pas nécessaire de rappeler `/api/auth/csrf`.
   - Sans token valide, la réponse est `403 "Access denied"`.
3. **Même site** :
   - En dev, le front tourne sur `http://localhost:5173` et l'API sur `http://localhost:8080`. Le CORS est configuré pour cette origine, avec les credentials.
   - En prod, le front doit être sur le même domaine parent que l'API (ex : `app.mondomaine.fr` et `api.mondomaine.fr`), car le cookie de session est `SameSite=Lax`.
4. **Savoir si l'utilisateur est connecté** : appeler `GET /api/me`. Un 200 renvoie le compte, un 401 signifie non connecté. La session expire après **7 jours d'inactivité**.
5. **Toute réponse 401** sur un endpoint protégé veut dire « non connecté » : il faut rediriger vers l'écran de connexion. Si le compte a été supprimé ailleurs, la session est fermée et renvoie aussi 401.

Base des URL : `http://localhost:8080` en dev, l'URL HTTPS de l'API en prod.
Toutes les routes sont sous `/api`. Le contenu est en JSON (`Content-Type: application/json`).

## 3. Format des erreurs (commun à tous les endpoints)

Les erreurs sont au format **Problem Details (RFC 9457)**, avec le content-type `application/problem+json` :

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400, "detail": "Validation failed", "instance": "/api/patterns" }
```

- **400 validation** : `detail` vaut `"Validation failed"`, avec en plus un tableau `errors` qui nomme chaque champ en faute. C'est ce tableau qu'il faut afficher sous les champs des formulaires.
  ```json
  { "status": 400, "detail": "Validation failed",
    "errors": [ { "field": "title", "message": "must not be blank" },
                { "field": "steps[0].rows[1].value", "message": "must not be blank" } ] }
  ```
  Le chemin d'un champ imbriqué s'écrit comme `steps[0].rows[1].value`. Un mauvais type ou une valeur d'enum inconnue donne aussi une entrée `errors`, par exemple `{ "field": "status", "message": "must be one of [IN_PROGRESS, FINISHED]" }`.
- **400 sans `errors`** : le JSON est illisible (syntaxe invalide).
- **401** : `"Authentication required"` quand on n'est pas connecté, ou `"Invalid email or password"` quand le login échoue. Ce message est volontairement identique, que l'email existe ou non.
- **403** : `"Access denied"`, quand le token CSRF est absent ou invalide.
- **404** : `"Pattern 12 not found"`. Le pattern n'existe pas **ou appartient à un autre compte**, et les deux cas sont indiscernables. Pour un avancement absent : `"Pattern 12 is not started"`.
- **409** : `"Email already used"`.
- **429** : `"Too many attempts, try again later"`, avec un en-tête **`Retry-After`** qui donne le délai en secondes, lisible depuis le front. Il faut afficher un message comme « Trop de tentatives, réessayez dans N minutes ».
- **500** : `"Internal error"`, ou une réponse sans corps exploitable. Il faut afficher un message générique.

## 4. Formats de données

Les dates sont en ISO-8601 UTC, par exemple `"2026-10-12T14:41:16.773123Z"`. Un champ facultatif sans valeur est renvoyé à `null`.

```ts
type ProgressStatus = 'IN_PROGRESS' | 'FINISHED';

interface Account { id: number; email: string; createdAt: string; }

interface Row { number: string; value: string; }
interface Step { title: string; rows: Row[]; }

interface Progress {
  status: ProgressStatus;
  stepIndex: number;          // 0-based
  rowIndex: number;           // 0-based
  startedAt: string;
  updatedAt: string;          // dernière action de l'utilisateur sur l'avancement
  finishedAt: string | null;  // renseigné quand le statut passe à FINISHED, remis à null si rouvert
}

interface Pattern {            // détail
  id: number;
  title: string;
  description: string | null;
  hookSizeMm: number | null;  // ex : 3.5
  yarn: string | null;
  sourceUrl: string | null;
  imageUrl: string | null;
  createdAt: string;
  updatedAt: string;
  steps: Step[];
  progress: Progress | null;  // null = non commencé
}

interface PatternSummary {     // élément de liste
  id: number;
  title: string;
  description: string | null;
  hookSizeMm: number | null;
  yarn: string | null;
  imageUrl: string | null;
  stepCount: number;
  progress: Progress | null;
}

interface PatternInput {       // corps de POST et de PUT /api/patterns
  title: string;               // obligatoire, non vide, ≤ 200 caractères
  description?: string | null;
  hookSizeMm?: number | null;  // > 0, au plus 2 chiffres avant la virgule et 2 après (max 99.99)
  yarn?: string | null;        // ≤ 200
  sourceUrl?: string | null;   // ≤ 500
  imageUrl?: string | null;    // ≤ 500
  steps: {                     // au moins 1 étape
    title: string;             // obligatoire, ≤ 200
    rows: {                    // au moins 1 rang
      number: string;          // obligatoire, ≤ 20
      value: string;           // obligatoire
    }[];
  }[];
}
```

L'ordre des étapes et des rangs est celui des tableaux envoyés. Aucun champ de position n'est à fournir.

## 5. Endpoints

### Authentification (accessibles sans être connecté)

| Méthode et route | Corps | Réponse |
|---|---|---|
| `GET /api/auth/csrf` | — | `204`, dépose le cookie `XSRF-TOKEN` |
| `POST /api/auth/register` | `{ "email", "password" }` | `201` + `Account`. L'utilisateur est **connecté** directement. Erreurs : `400` (champs), `409` (email pris), `429` |
| `POST /api/auth/login` | `{ "email", "password" }` | `200` + `Account`. Erreurs : `400`, `401` (identifiants invalides), `429` |
| `POST /api/auth/logout` | — | `204`. La session est fermée et un nouveau `XSRF-TOKEN` est déposé |

- **Email** : il est normalisé côté serveur (espaces retirés, minuscules) et doit être valide, avec au plus 254 caractères.
- **Mot de passe à l'inscription** : au moins 8 caractères et au plus 72 octets UTF-8, soit environ 72 caractères ASCII ou moins avec des accents.
- **Limite de tentatives** :
  - 5 échecs de connexion en 15 minutes pour un même email depuis une même adresse bloquent cette adresse pour cet email ;
  - 50 échecs au total sur un email le bloquent pour tout le monde ;
  - une même adresse a droit à 20 requêtes login ou inscription par minute.
  - Chaque refus renvoie une `429` avec `Retry-After`.

### Compte connecté

| Méthode et route | Corps | Réponse |
|---|---|---|
| `GET /api/me` | — | `200` + `Account`, ou `401` |
| `PUT /api/me/password` | `{ "currentPassword", "newPassword" }` | `204`. Les **autres** sessions du compte sont fermées, la session courante reste ouverte |
| `DELETE /api/me` | `{ "password" }` (oui, un corps sur un DELETE) | `204`. Le compte et **tous** ses patterns sont supprimés, et toutes les sessions sont fermées |

- **Erreurs sur le mot de passe** : un mot de passe actuel incorrect donne une `400` avec `errors: [{ "field": "currentPassword", "message": "is incorrect" }]`. Pour la suppression, le champ est `"password"`.
- **Nouveau mot de passe** : mêmes règles qu'à l'inscription.
- **Limite** : ces deux actions sont limitées comme le login, avec une `429` possible.

### Patterns (connecté obligatoire ; seulement ceux du compte)

| Méthode et route | Corps | Réponse |
|---|---|---|
| `GET /api/patterns` | — | `200` + `PatternSummary[]`, triés par titre sans tenir compte de la casse |
| `GET /api/patterns/{id}` | — | `200` + `Pattern`, ou `404` |
| `POST /api/patterns` | `PatternInput` | `201` + `Pattern`. L'en-tête `Location` pointe vers `/api/patterns/{id}` |
| `PUT /api/patterns/{id}` | `PatternInput` | `200` + `Pattern`, ou `404` |
| `DELETE /api/patterns/{id}` | — | `204`, ou `404`. L'avancement est supprimé avec |

- **`PUT` remplace tout le pattern** :
  - Un champ facultatif omis devient `null`.
  - Les étapes et les rangs sont entièrement remplacés par ceux envoyés.
  - Pour éditer, renvoyer le pattern complet modifié.
- **Effet sur l'avancement** : si le `PUT` supprime l'étape ou le rang où se trouvait l'utilisateur, l'avancement est **recadré** sur la dernière étape ou le dernier rang existant. Son statut et ses dates ne changent pas.

### Avancement d'un pattern

| Méthode et route | Corps | Réponse |
|---|---|---|
| `PUT /api/patterns/{id}/progress` | `{ "status", "stepIndex", "rowIndex" }` | `200` + `Progress` |
| `DELETE /api/patterns/{id}/progress` | — | `204` (abandon), ou `404 "… is not started"` si le pattern n'était pas commencé |

- **Usage du `PUT`** :
  - Le premier `PUT` **démarre** le pattern et fixe `startedAt`.
  - Les suivants déplacent la position et mettent à jour `updatedAt`.
  - `"FINISHED"` termine le pattern et fixe `finishedAt` au premier passage seulement.
  - Repasser à `"IN_PROGRESS"` **rouvre** le pattern et remet `finishedAt` à `null`.
- **Règles** :
  - Les trois champs sont obligatoires.
  - `stepIndex` doit être inférieur au nombre d'étapes, et `rowIndex` au nombre de rangs de cette étape. Sinon, la réponse est une `400` avec `errors: [{ "field": "rowIndex", "message": "must be less than 3" }]`.
- **Exemple :** pour « passer au rang suivant », le front calcule la nouvelle position, y compris le passage à l'étape suivante, puis envoie la position complète.
- **Où lire l'avancement :** il figure dans `progress` du détail (`GET /api/patterns/{id}`) et de chaque élément de la liste.

## 6. Exemple de client

```ts
const API = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

function xsrfToken(): string | undefined {
  return document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase();
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  if (method !== 'GET') {
    const token = xsrfToken();
    if (token) headers.set('X-XSRF-TOKEN', decodeURIComponent(token));
  }
  const res = await fetch(API + path, { ...init, method, headers, credentials: 'include' });
  if (res.status === 204) return undefined as T;
  const body = await res.json().catch(() => null);
  if (!res.ok) throw { status: res.status, problem: body, retryAfter: res.headers.get('Retry-After') };
  return body as T;
}

// au démarrage de l'app
await api('/api/auth/csrf');
```

Écrans attendus, à adapter :
- connexion et inscription ;
- liste des patterns, avec l'état de l'avancement de chacun ;
- détail d'un pattern, avec les étapes, les rangs et la position courante mise en évidence ;
- boutons démarrer, rang suivant ou précédent, terminer, rouvrir et abandonner ;
- création et édition d'un pattern (formulaire avec étapes et rangs dynamiques) ;
- compte : changer le mot de passe, supprimer le compte.
