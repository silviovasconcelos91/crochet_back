# Public Patterns, Usernames and Per-Account Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an owner mark a pattern `PRIVATE` or `PUBLIC`, let every logged-in account browse public patterns and follow them with its own progress, show authors by a username that also becomes the login identifier.

**Architecture:** `account` gains a username (display form + normalized key computed by one Java function) and logs in by it; the session principal becomes the account id. `pattern` gains a `visibility` column, and `pattern_progress` moves from one row per pattern to one row per (pattern, account), outside the `Pattern` aggregate. Reads are allowed to the owner or on public patterns; writes to the pattern stay owner-only (403 for a visible public pattern, 404 otherwise). Progress writes take a shared lock on the pattern row, pattern writes an exclusive one.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Security 7.1.1, Spring Session JDBC 4.1.1, Hibernate 7, PostgreSQL 17, Flyway, Lombok, JUnit 6, MockMvc, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-10-12-public-patterns-design.md` (builds on `docs/superpowers/specs/2026-10-12-accounts-progress-design.md`)

## Global Constraints

- Root package `vasconcelos.silvio.crochet_back`. No Java dependency from `pattern` to `account`: the author's username is read by a JPQL join on the `Account` entity name inside `PatternRepository` queries, never by importing `Account`.
- Migrations: V7 wipes `account`, `pattern`, `spring_session` (`TRUNCATE ... RESTART IDENTITY CASCADE`) and adds `account.username varchar(30)`, `account.username_key varchar(60)` with `uk_account_username_key`; V8 adds `pattern.visibility`; V9 recreates `pattern_progress` keyed by `(pattern_id, account_id)`. `ddl-auto: validate` stays.
- Username: `strip()` then NFC (`Usernames.normalize`), format `^[\p{L}\p{N}_.-]{3,30}$`, key = `Usernames.key` (normalize, `toLowerCase(Locale.ROOT)`, NFC again). Uniqueness, login lookup and the login throttle all use `Usernames.key`.
- Login body is `{ "username", "password" }`; register body is `{ "username", "email", "password" }`. Login never accepts an email: an email sent as `username` is an unknown username (401).
- Session principal name = `String.valueOf(accountId)` (`AccountPrincipal.getUsername()`).
- Errors: login failure 401 `Invalid username or password`; taken username/email 409 detail `Already used` with `errors: [{ "field": "username"|"email", "message": "is already used" }]`; another account's private pattern or a missing one 404 `Pattern <id> not found`; another account's public pattern written to 403 `Pattern <id> belongs to another account`.
- `visibility` is `"PRIVATE"` or `"PUBLIC"`; omitted on POST or PUT → `PRIVATE`.
- Responses: `Account` = `{ id, username, email, createdAt }`; `Pattern` and `PatternSummary` gain `visibility`, `author: { username }`, `mine`. `progress` is always the logged-in account's.
- Timestamps come from `Timestamps.now()`.
- Tests need Docker; run Maven from the repo root with `./mvnw` (Git Bash). `@Transactional` test classes call `TestDatabase.reset(jdbc)` in `@BeforeEach`.

## Review Focus

1. Logging in with an email in the `username` field must give 401 (not a 400 from the username format) → pinned in Task 2 (`loginWithEmailIsUnauthorized`).
2. A username typed with a decomposed accent (`"Amélie"`) must match the account registered as `"Amélie"` → pinned in Task 2 (`loginMatchesDecomposedAccents`).
3. A non-owner `PUT` on a public pattern must give 403 and leave the pattern untouched, visibility included → pinned in Task 6 (`otherAccountCannotWriteAPublicPattern`).
4. An owner `PUT` that keeps the pattern `PUBLIC` while changing steps must keep (and clamp) the followers' progress, not delete it → pinned in Task 7 (`publicReplaceClampsEveryAccountsProgress`).
5. A progress `PUT` on another account's private pattern must give 404 and insert no row → pinned in Task 6 (`progressOnAnotherAccountsPrivatePatternIsNotFoundAndWritesNothing`).

---

## File Structure

```
src/main/resources/db/migration/
  V7__add_account_username.sql            wipe + username columns
  V8__add_pattern_visibility.sql
  V9__make_progress_per_account.sql
src/main/java/vasconcelos/silvio/crochet_back/
  account/Usernames.java                  normalize, key, FORMAT
  account/dto/Username.java               @Pattern-based constraint (no validator class)
  account/AlreadyUsedException.java       replaces EmailAlreadyUsedException (fields list)
  account/Account.java                    + username, usernameKey, rename()
  account/AccountRepository.java          + findByUsernameKey, existsByUsernameKey
  account/AccountService.java             register(username, email, pw), changeUsername
  account/AccountPrincipal.java           (id, passwordHash); getUsername() = id
  account/AccountUserDetailsService.java  loads by username key
  account/AuthController.java             login/register by username
  account/MeController.java               + PUT /api/me/username; throttle key = username key
  account/dto/RegisterRequest.java, LoginRequest.java, ChangeUsernameRequest.java, AccountResponse.java
  pattern/Visibility.java                 enum
  pattern/Pattern.java                    + visibility; − progress, updateProgress, clearProgress
  pattern/PatternProgress.java            @IdClass(Key) (patternId, accountId)
  pattern/PatternProgressRepository.java  insertIfMissing, findForUpdate, deleteByKey, findAllByPatternId, deleteOthers
  pattern/PatternRepository.java          readable / for-share / for-update finders, summaries with author + progress
  pattern/PatternSummaryRow.java          + visibility, ownerId, authorUsername
  pattern/PatternService.java             access rules, private switch, clamp all
  pattern/PatternAccessDeniedException.java
  pattern/PatternController.java          + GET /api/patterns/public
  pattern/ProgressService.java            shared lock, per-account row
  pattern/PatternMapper.java              toResponse(pattern, progress, author, accountId), toSummary(row, accountId)
  pattern/dto/PatternRequest.java         + visibility
  pattern/dto/PatternResponse.java, PatternSummary.java  + visibility, author, mine
  pattern/dto/AuthorResponse.java
  config/ApiExceptionHandler.java         409 fields, 403 pattern, 401 wording
docs/front-api.md
```

---

### Task 1: Username normalization and format constraint

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/Usernames.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/dto/Username.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/account/UsernamesTest.java`

**Interfaces:**
- Produces: `Usernames.FORMAT` (`String` constant), `Usernames.normalize(String) → String` (null-safe), `Usernames.key(String) → String` (null-safe); annotation `@Username` (null is valid, combine with `@NotNull`).

- [ ] **Step 1: Write the failing test**

```java
package vasconcelos.silvio.crochet_back.account;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vasconcelos.silvio.crochet_back.account.dto.Username;

import static org.assertj.core.api.Assertions.assertThat;

class UsernamesTest {

    record Holder(@Username String username) {
    }

    final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void normalizeStripsAndComposesAccents() {
        assertThat(Usernames.normalize("  Amélie ")).isEqualTo("Amélie");
        assertThat(Usernames.normalize(null)).isNull();
    }

    @Test
    void keyIgnoresCaseAndAccentEncoding() {
        assertThat(Usernames.key("AméLIE")).isEqualTo(Usernames.key(" amélie"));
        assertThat(Usernames.key("Amélie")).isEqualTo("amélie");
        assertThat(Usernames.key(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"moi", "Amélie_42", "a.b-c", "李小龍", "abcdefghijklmnopqrstuvwxyz1234"})
    void acceptsLettersDigitsAndSeparators(String username) {
        assertThat(validator.validate(new Holder(username))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "abcdefghijklmnopqrstuvwxyz12345", "moi toi", "moi@exemple.fr", "moi!", ""})
    void rejectsOtherShapes(String username) {
        assertThat(validator.validate(new Holder(username)))
                .singleElement()
                .satisfies(v -> assertThat(v.getMessage())
                        .isEqualTo("must be 3 to 30 letters, digits, '_', '-' or '.'"));
    }

    @Test
    void nullIsLeftToNotNull() {
        assertThat(validator.validate(new Holder(null))).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=UsernamesTest`
Expected: compilation failure, `Usernames` and `Username` do not exist.

- [ ] **Step 3: Write the implementation**

`Usernames.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The single place usernames are compared: uniqueness, login lookup and the login throttle all use
 * {@link #key}, so they can never disagree on whether two spellings are the same name.
 */
public final class Usernames {

    /** Letters (accents included), digits, '_', '-', '.'; no space and no '@', so never an email. */
    public static final String FORMAT = "^[\\p{L}\\p{N}_.-]{3,30}$";

    private Usernames() {
    }

    /** Display form: surrounding whitespace removed, NFC (one encoding per accent). {@code null} stays {@code null}. */
    public static String normalize(String username) {
        return username == null ? null : Normalizer.normalize(username.strip(), Normalizer.Form.NFC);
    }

    /** Comparison form: {@link #normalize}d, lowercase, NFC again (lowercasing can decompose). */
    public static String key(String username) {
        String normalized = normalize(username);
        return normalized == null ? null
                : Normalizer.normalize(normalized.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
```

`dto/Username.java` (composed like `pattern.dto.NoNulCharacter`, so the native image needs no validator hint):

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import vasconcelos.silvio.crochet_back.account.Usernames;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** See {@link Usernames#FORMAT}. {@code null} is valid. */
@Pattern(regexp = Usernames.FORMAT)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
public @interface Username {

    String message() default "must be 3 to 30 letters, digits, '_', '-' or '.'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=UsernamesTest`
Expected: PASS (all cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/account/Usernames.java \
        src/main/java/vasconcelos/silvio/crochet_back/account/dto/Username.java \
        src/test/java/vasconcelos/silvio/crochet_back/account/UsernamesTest.java
git commit -m "feat: normalize and validate usernames"
```

---

### Task 2: Username on accounts, login by username, id as session principal

This task changes the register and login bodies, so every test that registers or logs in is migrated here. The suite must be green at the end of the task.

**Files:**
- Create: `src/main/resources/db/migration/V7__add_account_username.sql`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/AlreadyUsedException.java`
- Delete: `src/main/java/vasconcelos/silvio/crochet_back/account/EmailAlreadyUsedException.java`
- Modify: `account/Account.java`, `account/AccountRepository.java`, `account/AccountService.java`, `account/AccountPrincipal.java`, `account/AccountUserDetailsService.java`, `account/AuthController.java`, `account/MeController.java`, `account/dto/RegisterRequest.java`, `account/dto/LoginRequest.java`, `account/dto/AccountResponse.java`, `config/ApiExceptionHandler.java`
- Modify tests: `support/TestAccounts.java`, `account/AuthApiTests.java`, `account/LoginRateLimitApiTests.java`, `account/InfrastructureFailureApiTests.java`, `account/AccountManagementApiTests.java`, `account/AccountRepositoryTests.java`, `pattern/PatternRepositoryTests.java`, all `pattern/*ApiTests.java` that call `TestAccounts.create`, `SchemaMigrationTests.java`

**Interfaces:**
- Consumes: `Usernames.normalize`, `Usernames.key`, `@Username` (Task 1).
- Produces:
  - `new Account(String username, String email, String passwordHash)`; `Account.getUsername()`, `getUsernameKey()`, `rename(String username)`.
  - `AccountRepository.findByUsernameKey(String) → Optional<Account>`, `existsByUsernameKey(String) → boolean`.
  - `AccountService.register(String username, String email, String rawPassword) → Account`.
  - `new AccountPrincipal(Long id, String passwordHash)`; `id()`; `getUsername()` returns `String.valueOf(id)`.
  - `AlreadyUsedException(List<String> fields)`; `getFields()`.
  - Test helper `TestAccounts.create(AccountRepository, PasswordEncoder, String username) → AccountPrincipal` (email = `Usernames.key(username) + "@exemple.fr"`).
  - `AccountService.violates(DataIntegrityViolationException, String)` stays private; Task 3 reuses it from inside `AccountService`.

- [ ] **Step 1: Write the migration**

`V7__add_account_username.sql`:

```sql
-- Accounts now log in by username: the existing accounts (and with them every pattern, step, row and
-- progress) are discarded, as are the sessions whose principal was an email (public patterns spec, section 2).
TRUNCATE account, pattern, spring_session RESTART IDENTITY CASCADE;

ALTER TABLE account
    ADD COLUMN username     VARCHAR(30) NOT NULL,
    -- computed in Java (Usernames.key); lowercasing can lengthen a few letters, hence 60
    ADD COLUMN username_key VARCHAR(60) NOT NULL,
    ADD CONSTRAINT uk_account_username_key UNIQUE (username_key);
```

- [ ] **Step 2: Write the failing API tests**

Add to `AuthApiTests` (helpers replace the old `credentials(email, password)` / `registered(email)`):

```java
    static String registration(String username, String email, String password) {
        return """
                { "username": "%s", "email": "%s", "password": "%s" }""".formatted(username, email, password);
    }

    /** Email derived from the username, for tests that do not care about it. */
    static String registration(String username, String password) {
        return registration(username, Usernames.key(username) + "@exemple.fr", password);
    }

    static String credentials(String username, String password) {
        return """
                { "username": "%s", "password": "%s" }""".formatted(username, password);
    }

    Browser registered(String username) throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(registration(username, PASSWORD)))
                .andExpect(status().isCreated());
        return browser;
    }

    Long accountId(String username) {
        return jdbc.queryForObject("select id from account where username_key = ?", Long.class, Usernames.key(username));
    }

    @Test
    void registerReturnsUsernameAndEmail() throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(registration(" Amélie ", "Amelie@Exemple.fr", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("Amélie"))
                .andExpect(jsonPath("$.email").value("amelie@exemple.fr"));
    }

    @Test
    void takenUsernameIgnoringCaseIsConflictOnUsername() throws Exception {
        registered("Moi");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(registration("MOI", "autre@exemple.fr", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Already used"))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("username"))
                .andExpect(jsonPath("$.errors[0].message").value("is already used"));
    }

    @Test
    void takenUsernameAndEmailAreBothReported() throws Exception {
        registered("moi");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(registration("moi", "moi@exemple.fr", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("username", "email")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "moi toi", "moi@exemple.fr"})
    void invalidUsernameIsBadRequest(String username) throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(registration(username, "x@exemple.fr", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("username")));
    }

    @Test
    void loginIgnoresUsernameCase() throws Exception {
        registered("Moi");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(" mOI ", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Moi"));
    }

    @Test
    void loginMatchesDecomposedAccents() throws Exception {
        registered("Amélie");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("Amélie", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void loginWithEmailIsUnauthorized() throws Exception {
        registered("moi");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    @Test
    void sessionPrincipalIsTheAccountId() throws Exception {
        registered("moi");

        Integer stored = jdbc.queryForObject("select count(*) from spring_session where principal_name = ?",
                Integer.class, String.valueOf(accountId("moi")));
        assertThat(stored).isEqualTo(1);
    }
```

Add `import static org.hamcrest.Matchers.containsInAnyOrder;`, `import org.junit.jupiter.params.provider.ValueSource;`.

In `LoginRateLimitApiTests` add:

```java
    @Test
    void usernameCaseVariantsShareOneCounter() throws Exception {
        String ip = "203.0.113.71";
        send("/api/auth/register", "203.0.113.70", "casse", PASSWORD).andExpect(status().isCreated());
        send("/api/auth/login", ip, "casse", "mauvais-1").andExpect(status().isUnauthorized());
        send("/api/auth/login", ip, "CASSE", "mauvais-2").andExpect(status().isUnauthorized());

        send("/api/auth/login", ip, "Casse", PASSWORD).andExpect(status().isTooManyRequests());
    }
```

`SchemaMigrationTests`:

```java
    @Test
    void usernameKeyIsUnique() {
        jdbc.update("insert into account (email, password_hash, created_at, username, username_key) "
                + "values ('a@x.fr', 'h', now(), 'Moi', 'moi')");

        assertThatThrownBy(() -> jdbc.update("insert into account (email, password_hash, created_at, username, username_key) "
                + "values ('b@x.fr', 'h', now(), 'MOI', 'moi')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("delete from account");
    }
```

(imports: `org.springframework.dao.DataIntegrityViolationException`, `static org.assertj.core.api.Assertions.assertThatThrownBy`).

- [ ] **Step 3: Run the new tests to verify they fail**

Run: `./mvnw test -Dtest='AuthApiTests,LoginRateLimitApiTests,SchemaMigrationTests'`
Expected: failures (`$.username` missing, register 400 on unknown shape, 401 detail mismatch).

- [ ] **Step 4: Implement the account side**

`Account.java` — add fields, constructor, `rename`:

```java
    @Column(nullable = false, length = 30)
    private String username;

    @Column(name = "username_key", nullable = false, length = 60)
    private String usernameKey;

    /** {@code username} and {@code email} must already be normalized ({@link Usernames}, {@link Emails}). */
    public Account(String username, String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        rename(username);
    }

    /** {@code username} must already be normalized; its key is derived here so the two never diverge. */
    public void rename(String username) {
        this.username = username;
        this.usernameKey = Usernames.key(username);
    }
```

(remove the old `(email, passwordHash)` constructor).

`AccountRepository.java` — add:

```java
    Optional<Account> findByUsernameKey(String usernameKey);

    boolean existsByUsernameKey(String usernameKey);
```

`AlreadyUsedException.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import java.util.List;

/** Registration or rename hit a unique field; {@code fields} are request field names ("username", "email"). */
public class AlreadyUsedException extends RuntimeException {

    private final List<String> fields;

    public AlreadyUsedException(List<String> fields) {
        super("Already used");
        this.fields = List.copyOf(fields);
    }

    public List<String> getFields() {
        return fields;
    }
}
```

Delete `EmailAlreadyUsedException.java`.

`AccountService.register`:

```java
    /** {@code username} and {@code email} must already be normalized. */
    @Transactional
    public Account register(String username, String email, String rawPassword) {
        List<String> taken = new ArrayList<>();
        if (accounts.existsByUsernameKey(Usernames.key(username))) {
            taken.add("username");
        }
        if (accounts.existsByEmail(email)) {
            taken.add("email");
        }
        if (!taken.isEmpty()) {
            throw new AlreadyUsedException(taken);
        }
        try {
            return accounts.saveAndFlush(new Account(username, email, passwordEncoder.encode(rawPassword)));
        } catch (DataIntegrityViolationException ex) {
            // registered concurrently
            if (violates(ex, "uk_account_username_key")) {
                throw new AlreadyUsedException(List.of("username"));
            }
            if (violates(ex, "uk_account_email")) {
                throw new AlreadyUsedException(List.of("email"));
            }
            throw ex;
        }
    }
```

In `changePassword`, replace `accountSessions.invalidateAllExcept(account.getEmail(), keptSessionId)` with `accountSessions.invalidateAllExcept(String.valueOf(account.getId()), keptSessionId)`.

`AccountPrincipal.java` — drop `email`:

```java
    @Serial
    private static final long serialVersionUID = 2L;

    private final Long id;
    private String passwordHash;

    public AccountPrincipal(Long id, String passwordHash) {
        this.id = id;
        this.passwordHash = passwordHash;
    }

    /** The account id: it is the session's principal name, so renaming the account keeps its sessions. */
    @Override
    public String getUsername() {
        return String.valueOf(id);
    }
```

`AccountUserDetailsService.loadUserByUsername`:

```java
        return accounts.findByUsernameKey(Usernames.key(username))
                .map(account -> new AccountPrincipal(account.getId(), account.getPasswordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
```

`dto/RegisterRequest.java`:

```java
public record RegisterRequest(
        @NotNull @Username String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @BcryptPassword String password) {

    public RegisterRequest {
        username = Usernames.normalize(username);
        email = Emails.normalize(email);
    }
}
```

`dto/LoginRequest.java` (no format check: an email must reach the lookup and fail as 401):

```java
/** The username is bounded because its key becomes a key of the in-memory login throttle. */
public record LoginRequest(@NotBlank @Size(max = 254) String username, @NotBlank String password) {

    public LoginRequest {
        username = Usernames.normalize(username);
    }
}
```

`dto/AccountResponse.java`:

```java
public record AccountResponse(Long id, String username, String email, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getUsername(), account.getEmail(), account.getCreatedAt());
    }
}
```

`AuthController.register`: `accountService.register(body.username(), body.email(), body.password())` and `new AccountPrincipal(account.getId(), null)`.

`AuthController.login`:

```java
        // reserved before hashing, so a locked username costs no BCrypt work; unknown usernames are
        // counted too, so a lock reveals nothing about which accounts exist
        Authentication authentication = loginAttempts.checkLogin(
                Usernames.key(body.username()), request.getRemoteAddr(), BadCredentialsException.class,
                () -> authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password())));
```

`MeController` — the throttle key must be the same as the login's, so it is read from the account, not from the principal (whose name is now the id):

```java
    private String throttleKey(AccountPrincipal principal) {
        return accountService.get(principal.id()).getUsernameKey();
    }
```

Use `throttleKey(principal)` as the first argument of both `loginAttempts.checkFromSession(...)` calls. In `delete`, compute `String key = throttleKey(principal);` **before** `checkFromSession` (the account is gone afterwards), and keep `accountSessions.invalidateAllExcept(principal.getUsername(), current.getId())` (principal name = id).

`ApiExceptionHandler`: replace the `EmailAlreadyUsedException` handler and the bad-credentials wording:

```java
    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentials() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    @ExceptionHandler(AlreadyUsedException.class)
    ProblemDetail handleAlreadyUsed(AlreadyUsedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setProperty("errors", ex.getFields().stream()
                .map(field -> Map.of("field", field, "message", "is already used"))
                .toList());
        return problem;
    }
```

- [ ] **Step 5: Migrate the existing tests**

Apply these rules everywhere under `src/test`:

- `TestAccounts.create`:

```java
    /** Inserts a real account row and returns the principal Spring Security builds for it. */
    public static AccountPrincipal create(AccountRepository accounts, PasswordEncoder encoder, String username) {
        Account account = accounts.save(
                new Account(username, Usernames.key(username) + "@exemple.fr", encoder.encode(PASSWORD)));
        return new AccountPrincipal(account.getId(), account.getPasswordHash());
    }
```

  Callers pass the part before `@`: `"moi@exemple.fr"` → `"moi"`, `"alice@exemple.fr"` → `"alice"`, `"bob@exemple.fr"` → `"bob"`.
- Login/register identifiers in `AuthApiTests` and `LoginRateLimitApiTests`: an identifier `"<name>@exemple.fr"` becomes `"<name>"` (`"MOI@exemple.fr"` → `"MOI"`, `"session-volee@exemple.fr"` → `"session-volee"`, `"ip-1@exemple.fr"` → `"ip-1"`...). Register bodies use `registration(...)`, login bodies `credentials(...)`.
  - In `LoginRateLimitApiTests`, copy the three helpers `registration(u, e, p)`, `registration(u, p)`, `credentials(u, p)` from `AuthApiTests` and make `send` pick the body:

```java
    ResultActions send(String path, String ip, String username, String password) throws Exception {
        String body = path.endsWith("/register") ? registration(username, password) : credentials(username, password);
        return new Browser(mvc).fetchCsrf()
                .perform(from(ip, post(path).contentType(APPLICATION_JSON).content(body)));
    }
```

  - Its two direct register calls (`assiege`, `session-volee`) use `registration("assiege", PASSWORD)` / `registration("session-volee", PASSWORD)`.
- Tests about the email field itself keep testing it through `registration(username, email, password)`:
  - `registerCreatesAccountAndOpensSession`: register `registration("moi", "Moi@Exemple.fr", PASSWORD)`, still expect `$.email` = `"moi@exemple.fr"`, also expect `$.username` = `"moi"`.
  - `registerWithTakenEmailIgnoringCaseIsConflict`: second registration `registration("autre", "  MOI@exemple.FR ", PASSWORD)`; expect `$.detail` = `"Already used"` and `$.errors[0].field` = `"email"`.
  - The parameterized invalid-registration cases on `"email"` use `registration("moi", "<bad email>", PASSWORD)`; the cases on `"password"` use `registration("moi", "<password>")`; the case `{ "email": "moi@exemple.fr" }` (no password) becomes `{ "username": "moi", "email": "moi@exemple.fr" }`.
  - Any login test that sent a non-normalized email (spaces/case) now sends the username with spaces/case (`" MOI "`).
- `sessionIsStoredInDatabaseWithHttpOnlyLaxSecureCookie`: the count query uses `principal_name = ?` with `String.valueOf(accountId("moi"))`.
- Any expectation of `"Invalid email or password"` becomes `"Invalid username or password"`; any of `"Email already used"` becomes `"Already used"`.
- `InfrastructureFailureApiTests` and `AccountManagementApiTests`: replace `EMAIL` by `static final String USERNAME = "moi";`; their single `credentials(password)` helper splits in two:

```java
    static String registration(String password) {
        return """
                { "username": "%s", "email": "moi@exemple.fr", "password": "%s" }""".formatted(USERNAME, password);
    }

    static String credentials(String password) {
        return """
                { "username": "%s", "password": "%s" }""".formatted(USERNAME, password);
    }
```

  `registered()` uses `registration(PASSWORD)`; `loggedIn(...)` and login calls keep `credentials(...)`. In `AccountManagementApiTests`, `accountSessions.invalidateAllExcept(EMAIL, "none")` becomes `accountSessions.invalidateAllExcept(String.valueOf(jdbc.queryForObject("select id from account", Long.class)), "none")`.
- `AccountRepositoryTests`: `new Account("moi", "moi@exemple.fr", "hash")`; `savesAndFindsAccountByEmail` also asserts `accounts.findByUsernameKey("moi")` finds the same id; add:

```java
    @Test
    void rejectsDuplicateUsernameKey() {
        accounts.saveAndFlush(new Account("Moi", "moi@exemple.fr", "hash"));

        assertThatThrownBy(() -> accounts.saveAndFlush(new Account("MOI", "autre@exemple.fr", "hash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
```

  `rejectsNonLowercaseEmail` uses `new Account("moi", "Moi@Exemple.fr", "hash")`; `registerReportsOnlyTheEmailUniqueConstraintAsAlreadyUsed` calls `accountService.register("moi", "Moi@Exemple.fr", "motdepasse-secret")`.
- `PatternRepositoryTests`: `new Account("moi", "moi@exemple.fr", "hash")`, `new Account("autre", "autre@exemple.fr", "hash")`.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, 0 failures, 0 errors (`NativeHintsTest` still passes: `AccountPrincipal` is still registered).

- [ ] **Step 7: Commit**

```bash
git add -A src/main/resources/db/migration/V7__add_account_username.sql src/main/java src/test/java
git commit -m "feat: log in by username and use the account id as session principal"
```

---

### Task 3: Change the username

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/dto/ChangeUsernameRequest.java`
- Modify: `account/AccountService.java`, `account/MeController.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/account/AccountManagementApiTests.java`

**Interfaces:**
- Consumes: `Account.rename`, `AccountRepository.existsByUsernameKey`, `AlreadyUsedException` (Task 2).
- Produces: `PUT /api/me/username` → `200 AccountResponse`; `AccountService.changeUsername(long id, String username) → Account`.

- [ ] **Step 1: Write the failing tests** (in `AccountManagementApiTests`)

```java
    static String usernameChange(String username) {
        return """
                { "username": "%s" }""".formatted(username);
    }

    @Test
    void changeUsernameKeepsTheSessionAndLogsInWithTheNewName() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/username").contentType(APPLICATION_JSON).content(usernameChange("Nouveau")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Nouveau"));
        browser.perform(get("/api/me")).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("Nouveau"));

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{ \"username\": \"nouveau\", \"password\": \"%s\" }".formatted(PASSWORD)))
                .andExpect(status().isOk());
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changeToAnotherAccountsNameInOtherCaseIsConflict() throws Exception {
        Browser browser = registered();
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content("{ \"username\": \"Pris\", \"email\": \"pris@exemple.fr\", \"password\": \"%s\" }"
                                .formatted(PASSWORD)))
                .andExpect(status().isCreated());

        browser.perform(put("/api/me/username").contentType(APPLICATION_JSON).content(usernameChange("PRIS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("username"));
    }

    @Test
    void changeToOwnNameInOtherCaseOnlyChangesTheDisplay() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/username").contentType(APPLICATION_JSON).content(usernameChange("MOI")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("MOI"));
    }

    @Test
    void invalidNewUsernameIsBadRequest() throws Exception {
        registered().perform(put("/api/me/username").contentType(APPLICATION_JSON).content(usernameChange("a b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("username"));
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest=AccountManagementApiTests`
Expected: the four new tests fail (404/405 on `/api/me/username`).

- [ ] **Step 3: Implement**

`dto/ChangeUsernameRequest.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotNull;
import vasconcelos.silvio.crochet_back.account.Usernames;

public record ChangeUsernameRequest(@NotNull @Username String username) {

    public ChangeUsernameRequest {
        username = Usernames.normalize(username);
    }
}
```

`AccountService`:

```java
    /** {@code username} must already be normalized. The sessions survive: their principal is the id. */
    @Transactional
    public Account changeUsername(long id, String username) {
        Account account = get(id);
        String key = Usernames.key(username);
        if (!key.equals(account.getUsernameKey()) && accounts.existsByUsernameKey(key)) {
            throw new AlreadyUsedException(List.of("username"));
        }
        account.rename(username);
        try {
            accounts.flush();
        } catch (DataIntegrityViolationException ex) {
            if (violates(ex, "uk_account_username_key")) {
                throw new AlreadyUsedException(List.of("username")); // taken concurrently
            }
            throw ex;
        }
        return account;
    }
```

`MeController`:

```java
    @PutMapping("/username")
    public AccountResponse changeUsername(
            @AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody ChangeUsernameRequest body) {
        return AccountResponse.from(accountService.changeUsername(principal.id(), body.username()));
    }
```

- [ ] **Step 4: Run to verify they pass**

Run: `./mvnw test -Dtest=AccountManagementApiTests`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/account src/test/java/vasconcelos/silvio/crochet_back/account/AccountManagementApiTests.java
git commit -m "feat: let an account change its username"
```

---

### Task 4: Pattern visibility (owner-only access unchanged)

**Files:**
- Create: `src/main/resources/db/migration/V8__add_pattern_visibility.sql`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/Visibility.java`
- Modify: `pattern/Pattern.java`, `pattern/PatternMapper.java`, `pattern/PatternRepository.java` (summary query), `pattern/PatternSummaryRow.java`, `pattern/dto/PatternRequest.java`, `pattern/dto/PatternResponse.java`, `pattern/dto/PatternSummary.java`
- Test: `pattern/PatternWriteApiTests.java`, `pattern/PatternMapperTest.java`, `SchemaMigrationTests.java`

**Interfaces:**
- Produces: `enum Visibility { PRIVATE, PUBLIC }`; `Pattern.getVisibility()/setVisibility(Visibility)`; `PatternRequest.visibility()` (last component, nullable); `PatternResponse.visibility()` and `PatternSummary.visibility()`; `PatternSummaryRow.visibility()` (8th component, after `stepCount`).

- [ ] **Step 1: Write the failing tests** (in `PatternWriteApiTests`)

```java
    @Test
    void createIsPrivateByDefault() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(get(location)).andExpect(jsonPath("$.visibility").value("PRIVATE"));
        mvc.perform(get("/api/patterns")).andExpect(jsonPath("$[0].visibility").value("PRIVATE"));
    }

    @Test
    void createCanBePublic() throws Exception {
        String location = create(mvc, PatternJson.withSteps("\"title\": \"Chat\", \"visibility\": \"PUBLIC\",",
                PatternJson.VALID_STEPS));

        mvc.perform(get(location)).andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void replaceWithoutVisibilityMakesThePatternPrivate() throws Exception {
        String location = create(mvc, PatternJson.withSteps("\"title\": \"Chat\", \"visibility\": \"PUBLIC\",",
                PatternJson.VALID_STEPS));

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(titled("Chat"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    void unknownVisibilityIsBadRequest() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON)
                        .content(PatternJson.withSteps("\"title\": \"Chat\", \"visibility\": \"SECRET\",",
                                PatternJson.VALID_STEPS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("visibility"));
    }
```

(add `import static ...PatternJson.titled;` / `CHAT` / `create` if missing).

`SchemaMigrationTests`:

```java
    @Test
    void patternVisibilityIsConstrained() {
        String check = jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_pattern_visibility'
                """, String.class);
        assertThat(check).contains("PRIVATE").contains("PUBLIC");
    }
```

In `PatternMapperTest`, every `new PatternRequest(...)` gains a trailing `null` (visibility); add:

```java
    @Test
    void applyFieldsDefaultsVisibilityToPrivate() {
        Pattern pattern = mapper.toEntity(request);
        pattern.setVisibility(Visibility.PUBLIC);

        mapper.applyFields(new PatternRequest("Chat", null, null, null, null, null, List.of(), null), pattern);

        assertThat(pattern.getVisibility()).isEqualTo(Visibility.PRIVATE);
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest='PatternWriteApiTests,PatternMapperTest,SchemaMigrationTests'`
Expected: compilation failure (`Visibility`, 8-arg `PatternRequest`).

- [ ] **Step 3: Implement**

`V8__add_pattern_visibility.sql`:

```sql
ALTER TABLE pattern
    ADD COLUMN visibility VARCHAR(10) NOT NULL DEFAULT 'PRIVATE',
    ADD CONSTRAINT ck_pattern_visibility CHECK (visibility IN ('PRIVATE', 'PUBLIC'));

-- the public catalogue lists PUBLIC patterns sorted like this
CREATE INDEX ix_pattern_public_title ON pattern (lower(title), id) WHERE visibility = 'PUBLIC';
```

`Visibility.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

/** PRIVATE: its owner only. PUBLIC: every logged-in account can read it and follow it with its own progress. */
public enum Visibility {
    PRIVATE,
    PUBLIC
}
```

`Pattern.java`:

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Visibility visibility = Visibility.PRIVATE;
```

`PatternRequest` — add last component `Visibility visibility` (no constraint; Jackson rejects unknown values with a field error already).

`PatternMapper.applyFields` — add:

```java
        // omitted means private, on PUT too: forgetting the field never publishes a pattern
        pattern.setVisibility(request.visibility() == null ? Visibility.PRIVATE : request.visibility());
```

`PatternResponse`: add `Visibility visibility` after `progress`; `toResponse` passes `pattern.getVisibility()`.
`PatternSummaryRow`: add `Visibility visibility` after `stepCount`; `findAllSummaries` selects `p.visibility` after `size(p.steps)`.
`PatternSummary`: add `Visibility visibility` after `progress`; `toSummary` passes `row.visibility()`.

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V8__add_pattern_visibility.sql src/main/java/vasconcelos/silvio/crochet_back/pattern src/test/java
git commit -m "feat: let the owner choose a pattern's visibility"
```

---

### Task 5: Progress per (pattern, account)

Access stays owner-only in this task; only the storage and its locking change.

**Files:**
- Create: `src/main/resources/db/migration/V9__make_progress_per_account.sql`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternProgressRepository.java`
- Modify: `pattern/PatternProgress.java`, `pattern/Pattern.java`, `pattern/PatternRepository.java`, `pattern/PatternService.java`, `pattern/ProgressService.java`, `pattern/PatternMapper.java`
- Test: `pattern/PatternRepositoryTests.java`, `pattern/PatternMapperTest.java`, `SchemaMigrationTests.java`, `pattern/ProgressApiTests.java` (unchanged expectations must stay green)

**Interfaces:**
- Produces:
  - `PatternProgress(Long patternId, Long accountId)` constructor (package-private), `getPatternId()`, `getAccountId()`, `update(...)`, `clampTo(List<Step>)`; `PatternProgress.Key(Long patternId, Long accountId)`.
  - `PatternProgressRepository extends JpaRepository<PatternProgress, PatternProgress.Key>`:
    - `int insertIfMissing(long patternId, long accountId, Instant now)`
    - `Optional<PatternProgress> findForUpdate(long patternId, long accountId)`
    - `int deleteByKey(long patternId, long accountId)`
    - `List<PatternProgress> findAllByPatternId(Long patternId)`
    - `int deleteOthers(long patternId, long keptAccountId)`
  - `PatternMapper.toResponse(Pattern pattern, PatternProgress progress) → PatternResponse` (Task 6 widens it).
  - `PatternRepository.findAllSummaries(Long accountId)` (progress joined for that account).

- [ ] **Step 1: Write the failing tests**

`SchemaMigrationTests`:

```java
    @Test
    void progressIsKeyedByPatternAndAccount() {
        List<String> key = jdbc.queryForList("""
                select a.attname from pg_index i
                join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
                where i.indrelid = 'pattern_progress'::regclass and i.indisprimary
                order by a.attname
                """, String.class);
        assertThat(key).containsExactly("account_id", "pattern_id");
    }
```

`PatternRepositoryTests` — replace `findByIdAndOwnerIdLoadsTheProgressInTheSameQuery` with:

```java
    @Autowired
    PatternProgressRepository progress;

    @Test
    void summariesCarryOnlyTheGivenAccountsProgress() {
        Long otherAccount = accounts.save(new Account("autre", "autre@exemple.fr", "hash")).getId();
        Long id = repository.save(pattern("Chat", 2)).getId();
        progress.insertIfMissing(id, otherAccount, Timestamps.now());
        em.flush();

        assertThat(repository.findAllSummaries(ownerId)).singleElement()
                .satisfies(row -> assertThat(row.status()).isNull());

        progress.insertIfMissing(id, ownerId, Timestamps.now());
        em.flush();
        assertThat(repository.findAllSummaries(ownerId)).singleElement()
                .satisfies(row -> assertThat(row.status()).isEqualTo(ProgressStatus.IN_PROGRESS));
    }

    @Test
    void insertIfMissingKeepsAnExistingRow() {
        Long id = repository.save(pattern("Chat", 2)).getId();
        em.flush();

        assertThat(progress.insertIfMissing(id, ownerId, Timestamps.now())).isEqualTo(1);
        assertThat(progress.insertIfMissing(id, ownerId, Timestamps.now())).isZero();
    }
```

(imports: `vasconcelos.silvio.crochet_back.Timestamps`). In `findAllSummariesAndFindByIdAreScopedToTheOwner`, replace `findByIdAndOwnerId` by `findReadable` (defined below; still owner-only here because nothing is public).

`PatternMapperTest` — replace `toResponseHasNoProgressUntilStartedThenMapsIt` with:

```java
    @Test
    void toProgressResponseMapsPositionsAndNull() {
        assertThat(mapper.toProgressResponse(null)).isNull();

        PatternProgress progress = new PatternProgress(1L, 2L);
        progress.update(ProgressStatus.IN_PROGRESS, 1, 0);

        ProgressResponse response = mapper.toProgressResponse(progress);
        assertThat(response.status()).isEqualTo(ProgressStatus.IN_PROGRESS);
        assertThat(response.stepIndex()).isEqualTo(1);
        assertThat(response.rowIndex()).isZero();
        assertThat(response.finishedAt()).isNull();
    }
```

Change `PatternProgress(Pattern)`-based code in that test file accordingly (`progress.update(...)` and the constructor must be package-private, the test is in the same package).

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest='SchemaMigrationTests,PatternRepositoryTests,PatternMapperTest'`
Expected: compilation failure (`PatternProgressRepository`, new constructor).

- [ ] **Step 3: Write the migration**

`V9__make_progress_per_account.sql`:

```sql
-- Progress belongs to (pattern, account): several accounts can follow one public pattern
-- (public patterns spec, section 2). V7 emptied the table, nothing to carry over.
DROP TABLE pattern_progress;

CREATE TABLE pattern_progress
(
    pattern_id    BIGINT      NOT NULL REFERENCES pattern (id) ON DELETE CASCADE,
    account_id    BIGINT      NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL,
    step_position INT         NOT NULL,
    row_position  INT         NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    CONSTRAINT pk_pattern_progress PRIMARY KEY (pattern_id, account_id),
    CONSTRAINT ck_progress_status CHECK (status IN ('IN_PROGRESS', 'FINISHED')),
    CONSTRAINT ck_progress_step_position CHECK (step_position >= 0),
    CONSTRAINT ck_progress_row_position CHECK (row_position >= 0),
    CONSTRAINT ck_progress_finished_at CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL))
);

CREATE INDEX ix_pattern_progress_account_id ON pattern_progress (account_id);
```

- [ ] **Step 4: Rewrite the entity and add its repository**

`PatternProgress.java` (keep `update` and `clampTo` bodies as they are):

```java
/**
 * One account's current step and row on one pattern; positions are 0-based like Step/PatternRow
 * positions. Not part of the Pattern aggregate: several accounts follow one public pattern.
 */
@Entity
@Table(name = "pattern_progress")
@IdClass(PatternProgress.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternProgress {

    @Id
    @Column(name = "pattern_id")
    private Long patternId;

    @Id
    @Column(name = "account_id")
    private Long accountId;

    // status, stepPosition, rowPosition, startedAt, updatedAt, finishedAt: unchanged

    PatternProgress(Long patternId, Long accountId) {
        this.patternId = patternId;
        this.accountId = accountId;
        this.startedAt = Timestamps.now();
    }

    // update(...) and clampTo(...): unchanged

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long patternId;
        private Long accountId;
    }
}
```

(imports: `jakarta.persistence.IdClass`, `lombok.AllArgsConstructor`, `lombok.Data`, `java.io.Serializable`; remove the `@MapsId`/`@OneToOne` imports.)

`PatternProgressRepository.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PatternProgressRepository extends JpaRepository<PatternProgress, PatternProgress.Key> {

    /**
     * Starts the progress at the first row if this account has none. A concurrent first write by the
     * same account (double click) waits for the other insert and then does nothing, instead of
     * failing on the primary key.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into pattern_progress
                (pattern_id, account_id, status, step_position, row_position, started_at, updated_at)
            values (:patternId, :accountId, 'IN_PROGRESS', 0, 0, :now, :now)
            on conflict do nothing
            """)
    int insertIfMissing(@Param("patternId") long patternId, @Param("accountId") long accountId,
                        @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pr from PatternProgress pr where pr.patternId = :patternId and pr.accountId = :accountId")
    Optional<PatternProgress> findForUpdate(@Param("patternId") long patternId, @Param("accountId") long accountId);

    @Modifying
    @Query("delete from PatternProgress pr where pr.patternId = :patternId and pr.accountId = :accountId")
    int deleteByKey(@Param("patternId") long patternId, @Param("accountId") long accountId);

    List<PatternProgress> findAllByPatternId(Long patternId);

    @Modifying
    @Query("delete from PatternProgress pr where pr.patternId = :patternId and pr.accountId <> :keptAccountId")
    int deleteOthers(@Param("patternId") long patternId, @Param("keptAccountId") long keptAccountId);
}
```

- [ ] **Step 5: Move the pattern side off the old one-to-one**

`Pattern.java`: delete the `progress` field, `updateProgress` and `clearProgress` (and unused imports).

`PatternRepository.java` — replace the three queries:

```java
    String READABLE = "p.id = :id and p.ownerId = :accountId";

    @Query("select p from Pattern p where " + READABLE)
    Optional<Pattern> findReadable(@Param("id") Long id, @Param("accountId") Long accountId);

    /** For a progress write: shared lock, so progress writes of different accounts run in parallel. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Pattern p where " + READABLE)
    Optional<Pattern> findReadableForShare(@Param("id") Long id, @Param("accountId") Long accountId);

    /** For a pattern write: exclusive lock, waits for the progress writes in flight and blocks new ones. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pattern p where " + READABLE)
    Optional<Pattern> findReadableForUpdate(@Param("id") Long id, @Param("accountId") Long accountId);

    @Query("""
            select new vasconcelos.silvio.crochet_back.pattern.PatternSummaryRow(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps), p.visibility,
                pr.status, pr.stepPosition, pr.rowPosition, pr.startedAt, pr.updatedAt, pr.finishedAt)
            from Pattern p
            left join PatternProgress pr on pr.patternId = p.id and pr.accountId = :accountId
            where p.ownerId = :accountId
            order by lower(p.title), p.id
            """)
    List<PatternSummaryRow> findAllSummaries(@Param("accountId") Long accountId);
```

`READABLE` is the only place Task 6 changes to open public patterns.

`PatternMapper.toResponse`:

```java
    /** {@code progress} is the logged-in account's, {@code null} when it has not started. */
    public PatternResponse toResponse(Pattern pattern, PatternProgress progress) {
        // ... same body, last arguments: steps, toProgressResponse(progress), pattern.getVisibility()
    }
```

`PatternService` — inject `PatternProgressRepository progress`; rename parameters `ownerId` → `accountId`; replace `find`/`findForUpdate`:

```java
    public PatternResponse get(long accountId, long id) {
        Pattern pattern = repository.findReadable(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
        return mapper.toResponse(pattern, progress.findById(new PatternProgress.Key(id, accountId)).orElse(null));
    }

    @Transactional
    public PatternResponse create(long accountId, PatternRequest request) {
        Pattern pattern = mapper.toEntity(request);
        pattern.setOwnerId(accountId);
        return mapper.toResponse(repository.save(pattern), null);
    }

    @Transactional
    public PatternResponse replace(long accountId, long id, PatternRequest request) {
        // locked: every account's progress is clamped against the new steps and must not move meanwhile
        Pattern pattern = findForWrite(accountId, id);
        mapper.applyFields(request, pattern);
        // Hibernate flushes inserts before orphan deletes; removing old steps first avoids
        // violating UNIQUE(pattern_id, position) when new steps reuse the same positions.
        pattern.getSteps().clear();
        repository.flush();
        mapper.toSteps(request.steps()).forEach(pattern::addStep);
        pattern.touch();
        repository.flush();
        progress.findAllByPatternId(id).forEach(row -> row.clampTo(pattern.getSteps()));
        progress.flush();
        return mapper.toResponse(pattern, progress.findById(new PatternProgress.Key(id, accountId)).orElse(null));
    }

    @Transactional
    public void delete(long accountId, long id) {
        // locked like every other write to the pattern; progress rows go through ON DELETE CASCADE
        repository.delete(findForWrite(accountId, id));
    }

    /** The pattern, exclusively locked, for its owner. */
    Pattern findForWrite(long accountId, long id) {
        return repository.findReadableForUpdate(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
    }

    /**
     * The pattern, share-locked, for a progress write. Public: called through the Spring proxy by
     * ProgressService, and a non-public method on a CGLIB proxy is not a safe call.
     */
    public Pattern findForProgress(long accountId, long id) {
        return repository.findReadableForShare(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
    }
```

`ProgressService`:

```java
    private final PatternService patterns;
    private final PatternProgressRepository progress;
    private final PatternMapper mapper;

    public ProgressResponse update(long accountId, long patternId, ProgressRequest request) {
        Pattern pattern = patterns.findForProgress(accountId, patternId);
        List<Step> steps = pattern.getSteps();
        int stepIndex = request.stepIndex();
        int rowIndex = request.rowIndex();
        if (stepIndex >= steps.size()) {
            throw new InvalidFieldException("stepIndex", "must be less than " + steps.size());
        }
        int rowCount = steps.get(stepIndex).getRows().size();
        if (rowIndex >= rowCount) {
            throw new InvalidFieldException("rowIndex", "must be less than " + rowCount);
        }
        progress.insertIfMissing(patternId, accountId, Timestamps.now());
        PatternProgress row = progress.findForUpdate(patternId, accountId).orElseThrow();
        row.update(request.status(), stepIndex, rowIndex);
        progress.flush();
        return mapper.toProgressResponse(row);
    }

    public void abandon(long accountId, long patternId) {
        patterns.findForProgress(accountId, patternId);
        if (progress.deleteByKey(patternId, accountId) == 0) {
            throw new ProgressNotFoundException(patternId);
        }
    }
```

`PatternService.list` passes `accountId` to `findAllSummaries` (unchanged signature shape).

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS — in particular every `ProgressApiTests` case (start, finish keeps `finishedAt` on second FINISHED, reopen, abandon, clamping, 404 for another account) passes unchanged.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V9__make_progress_per_account.sql src/main/java/vasconcelos/silvio/crochet_back/pattern src/test/java
git commit -m "feat: store progress per pattern and account"
```

---

### Task 6: Public patterns: read access, author, catalogue, 403 on writes

**Files:**
- Create: `pattern/PatternAccessDeniedException.java`, `pattern/dto/AuthorResponse.java`
- Create test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PublicPatternsApiTests.java`
- Modify: `pattern/PatternRepository.java`, `pattern/PatternSummaryRow.java`, `pattern/PatternMapper.java`, `pattern/PatternService.java`, `pattern/PatternController.java`, `pattern/dto/PatternResponse.java`, `pattern/dto/PatternSummary.java`, `config/ApiExceptionHandler.java`
- Modify test: `pattern/PatternOwnershipApiTests.java` (its private-pattern 404 cases must keep passing as is)

**Interfaces:**
- Consumes: `findReadable*`, `PatternProgressRepository` (Task 5), `Visibility` (Task 4).
- Produces: `GET /api/patterns/public`; `PatternResponse` / `PatternSummary` end with `(..., ProgressResponse progress, Visibility visibility, AuthorResponse author, boolean mine)`; `PatternSummaryRow` gains `ownerId` and `authorUsername` after `visibility`; `PatternMapper.toResponse(Pattern, PatternProgress, String authorUsername, long accountId)`, `toSummary(PatternSummaryRow, long accountId)`; `PatternRepository.findAuthorUsername(Long ownerId) → String`, `findPublicSummaries(Long accountId)`.

- [ ] **Step 1: Write the failing tests**

`PublicPatternsApiTests.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.VALID_STEPS;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.withSteps;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PublicPatternsApiTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc alice;
    MockMvc bob;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        alice = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "Alice"));
        bob = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "Bob"));
    }

    static String pattern(String title, String visibility) {
        return withSteps("\"title\": \"%s\", \"visibility\": \"%s\",".formatted(title, visibility), VALID_STEPS);
    }

    static String progress(int stepIndex, int rowIndex) {
        return """
                { "status": "IN_PROGRESS", "stepIndex": %d, "rowIndex": %d }""".formatted(stepIndex, rowIndex);
    }

    int progressRows() {
        return jdbc.queryForObject("select count(*) from pattern_progress", Integer.class);
    }

    @Test
    void otherAccountReadsAPublicPatternWithItsAuthor() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));

        bob.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Chat"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.author.username").value("Alice"))
                .andExpect(jsonPath("$.mine").value(false))
                .andExpect(jsonPath("$.progress").doesNotExist());
        alice.perform(get(location)).andExpect(jsonPath("$.mine").value(true));
    }

    @Test
    void otherAccountCannotWriteAPublicPattern() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));
        String id = location.substring(location.lastIndexOf('/') + 1);

        bob.perform(put(location).contentType(APPLICATION_JSON).content(pattern("Volé", "PRIVATE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Pattern " + id + " belongs to another account"));
        bob.perform(delete(location)).andExpect(status().isForbidden());

        alice.perform(get(location))
                .andExpect(jsonPath("$.title").value("Chat"))
                .andExpect(jsonPath("$.visibility").value("PUBLIC"));
    }

    @Test
    void privatePatternStaysInvisibleToOthers() throws Exception {
        String location = create(alice, pattern("Secret", "PRIVATE"));

        bob.perform(get(location)).andExpect(status().isNotFound());
        bob.perform(put(location).contentType(APPLICATION_JSON).content(pattern("Volé", "PUBLIC")))
                .andExpect(status().isNotFound());
        bob.perform(delete(location)).andExpect(status().isNotFound());
        bob.perform(get("/api/patterns/public")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void progressOnAnotherAccountsPrivatePatternIsNotFoundAndWritesNothing() throws Exception {
        String location = create(alice, pattern("Secret", "PRIVATE"));

        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isNotFound());
        bob.perform(delete(location + "/progress")).andExpect(status().isNotFound());

        assertThat(progressRows()).isZero();
    }

    @Test
    void catalogueListsEveryPublicPatternSortedWithMyProgress() throws Exception {
        create(alice, pattern("zèbre", "PUBLIC"));
        create(alice, pattern("Caché", "PRIVATE"));
        String chat = create(bob, pattern("Chat", "PUBLIC"));
        alice.perform(put(chat + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());

        alice.perform(get("/api/patterns/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Chat"))
                .andExpect(jsonPath("$[0].author.username").value("Bob"))
                .andExpect(jsonPath("$[0].mine").value(false))
                .andExpect(jsonPath("$[0].progress.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[1].title").value("zèbre"))
                .andExpect(jsonPath("$[1].mine").value(true))
                .andExpect(jsonPath("$[1].progress").doesNotExist());
        bob.perform(get("/api/patterns/public")).andExpect(jsonPath("$[0].progress").doesNotExist());
    }

    @Test
    void myListStaysMinePublicOrNot() throws Exception {
        create(alice, pattern("Public", "PUBLIC"));
        create(alice, pattern("Privé", "PRIVATE"));
        create(bob, pattern("De Bob", "PUBLIC"));

        alice.perform(get("/api/patterns"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mine").value(true))
                .andExpect(jsonPath("$[0].author.username").value("Alice"));
    }

    @Test
    void twoAccountsFollowOnePatternIndependently() throws Exception {
        String location = create(alice, withSteps("\"title\": \"Chat\", \"visibility\": \"PUBLIC\",", """
                [ { "title": "A", "rows": [ { "number": "1", "value": "6ms" }, { "number": "2", "value": "6aug" } ] } ]"""));

        alice.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 1)))
                .andExpect(status().isOk());
        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());

        alice.perform(get(location)).andExpect(jsonPath("$.progress.rowIndex").value(1));
        bob.perform(get(location)).andExpect(jsonPath("$.progress.rowIndex").value(0));

        bob.perform(delete(location + "/progress")).andExpect(status().isNoContent());
        alice.perform(get(location)).andExpect(jsonPath("$.progress.rowIndex").value(1));
        bob.perform(get(location)).andExpect(jsonPath("$.progress").doesNotExist());
    }

    @Test
    void authorFollowsARename() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));
        jdbc.update("update account set username = 'Alicia', username_key = 'alicia' where username_key = 'alice'");

        bob.perform(get(location)).andExpect(jsonPath("$.author.username").value("Alicia"));
        bob.perform(get("/api/patterns/public")).andExpect(jsonPath("$[0].author.username").value("Alicia"));
    }

    @Test
    void deletingAFollowersAccountKeepsThePattern() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));
        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());

        jdbc.update("delete from account where username_key = 'bob'");

        assertThat(progressRows()).isZero();
        alice.perform(get(location)).andExpect(status().isOk());
    }
}
```

In `PatternOwnershipApiTests`, nothing changes: all its patterns are created without `visibility`, so they are private and the existing 404 expectations hold. Also add to `PatternApiTests.getReturnsCreatedPatternWithStepAndRowOrder`: `.andExpect(jsonPath("$.author.username").value("moi")).andExpect(jsonPath("$.mine").value(true))`.

- [ ] **Step 2: Run to verify they fail**

Run: `./mvnw test -Dtest='PublicPatternsApiTests,PatternApiTests'`
Expected: failures (404 for Bob on public patterns, no `author`/`mine`, 404 on `/api/patterns/public` parsed as id → 400).

- [ ] **Step 3: Implement**

`PatternAccessDeniedException.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

/** A public pattern of another account: visible, so refusing a write with 404 would be a lie. */
public class PatternAccessDeniedException extends RuntimeException {

    public PatternAccessDeniedException(long id) {
        super("Pattern " + id + " belongs to another account");
    }
}
```

`ApiExceptionHandler`:

```java
    @ExceptionHandler(PatternAccessDeniedException.class)
    ProblemDetail handlePatternAccessDenied(PatternAccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }
```

`dto/AuthorResponse.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

public record AuthorResponse(String username) {
}
```

`PatternResponse`: append `AuthorResponse author, boolean mine` after `visibility`. `PatternSummary`: append the same after `visibility`.

`PatternSummaryRow`: insert `Long ownerId, String authorUsername` right after `visibility`.

`PatternRepository`:

```java
    /** Owner, or anyone for a public pattern. Writes check ownership on top (PatternService). */
    String READABLE = "p.id = :id and (p.ownerId = :accountId"
            + " or p.visibility = vasconcelos.silvio.crochet_back.pattern.Visibility.PUBLIC)";

    String SUMMARY = """
            select new vasconcelos.silvio.crochet_back.pattern.PatternSummaryRow(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps), p.visibility,
                p.ownerId, a.username,
                pr.status, pr.stepPosition, pr.rowPosition, pr.startedAt, pr.updatedAt, pr.finishedAt)
            from Pattern p
            join Account a on a.id = p.ownerId
            left join PatternProgress pr on pr.patternId = p.id and pr.accountId = :accountId
            """;

    @Query(SUMMARY + " where p.ownerId = :accountId order by lower(p.title), p.id")
    List<PatternSummaryRow> findAllSummaries(@Param("accountId") Long accountId);

    @Query(SUMMARY + " where p.visibility = vasconcelos.silvio.crochet_back.pattern.Visibility.PUBLIC"
            + " order by lower(p.title), p.id")
    List<PatternSummaryRow> findPublicSummaries(@Param("accountId") Long accountId);

    /** Read through the entity name: the pattern package has no Java dependency on account. */
    @Query("select a.username from Account a where a.id = :ownerId")
    String findAuthorUsername(@Param("ownerId") Long ownerId);
```

`PatternMapper`:

```java
    public PatternResponse toResponse(Pattern pattern, PatternProgress progress, String authorUsername, long accountId) {
        // ... same body, last arguments:
        //     steps, toProgressResponse(progress), pattern.getVisibility(),
        //     new AuthorResponse(authorUsername), pattern.getOwnerId() == accountId
    }

    public PatternSummary toSummary(PatternSummaryRow row, long accountId) {
        ProgressResponse progress = row.status() == null ? null : new ProgressResponse(
                row.status(), row.stepPosition(), row.rowPosition(),
                row.startedAt(), row.updatedAt(), row.finishedAt());
        return new PatternSummary(
                row.id(), row.title(), row.description(), row.hookSizeMm(), row.yarn(), row.imageUrl(),
                row.stepCount(), progress, row.visibility(), new AuthorResponse(row.authorUsername()),
                row.ownerId() == accountId);
    }
```

`PatternService`:

```java
    public List<PatternSummary> list(long accountId) {
        return repository.findAllSummaries(accountId).stream().map(row -> mapper.toSummary(row, accountId)).toList();
    }

    public List<PatternSummary> listPublic(long accountId) {
        return repository.findPublicSummaries(accountId).stream().map(row -> mapper.toSummary(row, accountId)).toList();
    }

    public PatternResponse get(long accountId, long id) {
        Pattern pattern = repository.findReadable(id, accountId).orElseThrow(() -> new PatternNotFoundException(id));
        return respond(pattern, accountId);
    }

    private PatternResponse respond(Pattern pattern, long accountId) {
        PatternProgress mine = progress.findById(new PatternProgress.Key(pattern.getId(), accountId)).orElse(null);
        return mapper.toResponse(pattern, mine, repository.findAuthorUsername(pattern.getOwnerId()), accountId);
    }

    /** The pattern, exclusively locked, for its owner: 404 when invisible, 403 when public but not theirs. */
    Pattern findForWrite(long accountId, long id) {
        Pattern pattern = repository.findReadableForUpdate(id, accountId)
                .orElseThrow(() -> new PatternNotFoundException(id));
        if (pattern.getOwnerId() != accountId) {
            throw new PatternAccessDeniedException(id);
        }
        return pattern;
    }
```

`create` and `replace` return `respond(pattern, accountId)` (after the final flush).

`PatternController`:

```java
    @GetMapping("/public")
    public List<PatternSummary> listPublic(@AuthenticationPrincipal AccountPrincipal principal) {
        return service.listPublic(principal.id());
    }
```

(`/public` is a literal segment: Spring MVC prefers it over `/{id}`.)

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS (`PatternOwnershipApiTests` and `ProgressApiTests.otherAccountCannotTouchProgress` still pass: their patterns are private).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back src/test/java/vasconcelos/silvio/crochet_back/pattern
git commit -m "feat: open public patterns to every account with its own progress"
```

---

### Task 7: Going private drops followers; concurrency guarantees

**Files:**
- Modify: `pattern/PatternService.java`
- Create test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/ProgressConcurrencyTests.java`
- Modify test: `pattern/PublicPatternsApiTests.java`

**Interfaces:**
- Consumes: `PatternProgressRepository.deleteOthers`, `findAllByPatternId` (Task 5); `ProgressService.update(long accountId, long patternId, ProgressRequest)`.

- [ ] **Step 1: Write the failing tests**

Add to `PublicPatternsApiTests`:

```java
    @Test
    void goingPrivateDropsFollowersButKeepsTheOwner() throws Exception {
        String location = create(alice, pattern("Chat", "PUBLIC"));
        alice.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());
        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isOk());

        alice.perform(put(location).contentType(APPLICATION_JSON).content(pattern("Chat", "PRIVATE")))
                .andExpect(status().isOk());

        assertThat(progressRows()).isEqualTo(1);
        alice.perform(get(location)).andExpect(jsonPath("$.progress.status").value("IN_PROGRESS"));
        bob.perform(get(location)).andExpect(status().isNotFound());
        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 0)))
                .andExpect(status().isNotFound());

        alice.perform(put(location).contentType(APPLICATION_JSON).content(pattern("Chat", "PUBLIC")))
                .andExpect(status().isOk());
        bob.perform(get(location)).andExpect(jsonPath("$.progress").doesNotExist());
    }

    @Test
    void publicReplaceClampsEveryAccountsProgress() throws Exception {
        String location = create(alice, withSteps("\"title\": \"Chat\", \"visibility\": \"PUBLIC\",", """
                [ { "title": "A", "rows": [ { "number": "1", "value": "6ms" }, { "number": "2", "value": "6aug" } ] },
                  { "title": "B", "rows": [ { "number": "1", "value": "12ms" } ] } ]"""));
        alice.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(1, 0)))
                .andExpect(status().isOk());
        bob.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(progress(0, 1)))
                .andExpect(status().isOk());

        alice.perform(put(location).contentType(APPLICATION_JSON).content(pattern("Chat", "PUBLIC")))
                .andExpect(status().isOk()); // VALID_STEPS: one step, one row

        assertThat(progressRows()).isEqualTo(2);
        alice.perform(get(location))
                .andExpect(jsonPath("$.progress.stepIndex").value(0)).andExpect(jsonPath("$.progress.rowIndex").value(0));
        bob.perform(get(location))
                .andExpect(jsonPath("$.progress.stepIndex").value(0)).andExpect(jsonPath("$.progress.rowIndex").value(0));
    }
```

`ProgressConcurrencyTests.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.RowRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.StepRequest;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProgressConcurrencyTests {

    static final ProgressRequest START = new ProgressRequest(ProgressStatus.IN_PROGRESS, 0, 0);

    @Autowired
    PatternService patterns;

    @Autowired
    ProgressService progress;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    long alice;
    long bob;
    long patternId;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        alice = TestAccounts.create(accounts, passwordEncoder, "alice").id();
        bob = TestAccounts.create(accounts, passwordEncoder, "bob").id();
        patternId = patterns.create(alice, new PatternRequest("Chat", null, null, null, null, null,
                List.of(new StepRequest("A", List.of(new RowRequest("1", "6ms")))), Visibility.PUBLIC)).id();
    }

    /** Runs {@code first} in a transaction left open until the returned latch is released. */
    CountDownLatch holdOpen(Runnable first, CompletableFuture<Void> done) {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            first.run();
            started.countDown();
            await(release);
        })).whenComplete((ok, error) -> {
            if (error != null) {
                done.completeExceptionally(error);
            } else {
                done.complete(null);
            }
        });
        await(started);
        return release;
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void twoAccountsWriteProgressWithoutWaitingForEachOther() throws Exception {
        CompletableFuture<Void> aliceDone = new CompletableFuture<>();
        CountDownLatch release = holdOpen(() -> progress.update(alice, patternId, START), aliceDone);
        try {
            // Alice's transaction still holds its share lock on the pattern row
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> progress.update(bob, patternId, START));
        } finally {
            release.countDown();
        }
        aliceDone.get(10, TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentFirstWritesOfOneAccountDoNotFail() throws Exception {
        CompletableFuture<Void> firstDone = new CompletableFuture<>();
        CountDownLatch release = holdOpen(() -> progress.update(bob, patternId, START), firstDone);
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> progress.update(bob, patternId,
                new ProgressRequest(ProgressStatus.FINISHED, 0, 0)));
        // the second insert waits on the first one's uncommitted row
        while (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'",
                Integer.class) == 0) {
            Thread.onSpinWait();
        }
        release.countDown();

        firstDone.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("select status from pattern_progress", String.class)).isEqualTo("FINISHED");
    }
}
```

- [ ] **Step 2: Run to verify which fail**

Run: `./mvnw test -Dtest='PublicPatternsApiTests,ProgressConcurrencyTests'`
Expected: `goingPrivateDropsFollowersButKeepsTheOwner` fails (2 progress rows remain). `publicReplaceClampsEveryAccountsProgress` and both concurrency tests should already pass thanks to Task 5 — they pin the behaviour; if one fails, fix the locking in Task 5's code before going on.

- [ ] **Step 3: Implement the private switch** (in `PatternService.replace`)

```java
        Pattern pattern = findForWrite(accountId, id);
        boolean wasPublic = pattern.getVisibility() == Visibility.PUBLIC;
        mapper.applyFields(request, pattern);
        // ... steps replaced and flushed as before ...
        if (wasPublic && pattern.getVisibility() == Visibility.PRIVATE) {
            // private means the owner's alone: the followers lose their access and their progress
            progress.deleteOthers(id, accountId);
        }
        progress.findAllByPatternId(id).forEach(row -> row.clampTo(pattern.getSteps()));
        progress.flush();
        return respond(pattern, accountId);
```

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternService.java src/test/java/vasconcelos/silvio/crochet_back/pattern
git commit -m "feat: drop followers' progress when a pattern goes private"
```

---

### Task 8: Front API documentation

**Files:**
- Modify: `docs/front-api.md`

- [ ] **Step 1: Update the document** — apply each change below, keeping the existing tone (French, short lists):

  - **§1 Concepts métier**:
    - « Compte » : `un utilisateur s'inscrit avec un **pseudo**, un email et un mot de passe, et se connecte avec son **pseudo**. Il voit et modifie ses patterns ; il peut aussi consulter les patterns **publics** des autres.`
    - New bullet « Visibilité » : `PRIVATE` (moi seul) ou `PUBLIC` (tous les comptes connectés, en lecture). Seul l'auteur modifie ou supprime. Repasser en privé coupe l'accès des autres et **supprime leur avancement**.
    - « Avancement » : `propre à chaque compte : deux comptes avancent sur le même pattern public sans se voir ni se gêner.`
  - **§4 Formats**:

```ts
type Visibility = 'PRIVATE' | 'PUBLIC';

interface Account { id: number; username: string; email: string; createdAt: string; }

interface Author { username: string; }
```

    `Pattern` and `PatternSummary` gain `visibility: Visibility; author: Author; mine: boolean;  // mine = je suis l'auteur, afficher l'édition`. `PatternInput` gains `visibility?: Visibility; // omis = 'PRIVATE', y compris sur PUT`.
  - **§5 Authentification** table:
    - `POST /api/auth/register` | `{ "username", "email", "password" }` | `201` + `Account`, connecté. Erreurs : `400`, `409` (pseudo et/ou email pris), `429`.
    - `POST /api/auth/login` | `{ "username", "password" }` | `200` + `Account`. Erreurs : `400`, `401`, `429`.
    - Bullets: **Pseudo** : 3 à 30 caractères, lettres (accents acceptés), chiffres, `_`, `-`, `.` ; ni espace ni `@`. Unique sans tenir compte de la casse ; la casse saisie est affichée. Connexion sans tenir compte de la casse. **L'email ne permet pas de se connecter.**
    - **409** : `errors: [{ "field": "username" | "email", "message": "is already used" }]`, les deux si les deux sont pris.
    - Limite de tentatives : « pour un même email » devient « pour un même pseudo ».
  - **§5 Compte connecté** table: add `PUT /api/me/username` | `{ "username" }` | `200` + `Account`. Erreurs : `400`, `409`. La session reste ouverte.
  - **§5 Patterns**: title becomes `### Patterns (connecté obligatoire)`; table:

| Méthode et route | Corps | Réponse |
|---|---|---|
| `GET /api/patterns` | — | `200` + `PatternSummary[]` : **mes** patterns, publics et privés, triés par titre |
| `GET /api/patterns/public` | — | `200` + `PatternSummary[]` : **tous** les patterns publics (les miens compris), triés par titre, avec mon avancement |
| `GET /api/patterns/{id}` | — | `200` + `Pattern` si j'en suis l'auteur ou s'il est public, sinon `404` |
| `POST /api/patterns` | `PatternInput` | `201` + `Pattern` |
| `PUT /api/patterns/{id}` | `PatternInput` | `200` + `Pattern`. `403` sur le pattern public d'un autre, `404` sur un pattern invisible |
| `DELETE /api/patterns/{id}` | — | `204`. `403` / `404` comme le `PUT`. L'avancement de tout le monde est supprimé avec |

    Bullets: repasser en `PRIVATE` supprime l'avancement des autres comptes ; un `PUT` qui modifie les étapes recadre l'avancement de **chaque** compte. Pas de pagination du catalogue pour l'instant.
  - **§5 Avancement**: `PUT`/`DELETE …/progress` possibles sur mes patterns et sur tout pattern public ; `404` sinon. `progress` (détail et listes) est toujours **celui du compte connecté**.
  - **§6 Exemple de client**: any `login(email, password)` helper becomes `login(username, password)` sending `{ username, password }`; `register` sends `{ username, email, password }`.

- [ ] **Step 2: Check consistency**

Run: `grep -n "email" docs/front-api.md`
Expected: every remaining mention is the email field of register / `Account` / the email `409`, none describes logging in with it.

- [ ] **Step 3: Commit**

```bash
git add docs/front-api.md
git commit -m "docs: describe usernames, public patterns and per-account progress for the front end"
```
