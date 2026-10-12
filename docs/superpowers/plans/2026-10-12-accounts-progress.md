# Accounts and Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add accounts with session login, make patterns private to their owner, and let each owner start, advance, finish, reopen and abandon a pattern, storing the current step and row.

**Architecture:** New `account` package (entity, Spring Security principal and user-details service, auth and `/api/me` controllers). Spring Security 7 secures `/api/**`. It uses session login, SPA-mode CSRF, CORS inside the security chain, and 401/403 rendered as `ProblemDetail` through the MVC exception resolver. Spring Session JDBC stores sessions in Postgres. `Pattern` gains an `ownerId` and a 1-1 `PatternProgress` that is part of the pattern aggregate. Progress stores 0-based positions and is clamped when a pattern is replaced.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring Security 7.1.1, Spring Session 4.1.1 (JDBC), Hibernate 7.4, PostgreSQL 17, Flyway 12, Lombok, JUnit 5, MockMvc, spring-security-test, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-10-12-accounts-progress-design.md` (builds on `docs/superpowers/specs/2026-10-12-crochet-patterns-design.md`)

## Global Constraints

- Root package `vasconcelos.silvio.crochet_back`. New packages: `...account`, `...account.dto`. Progress lives in `...pattern` (aggregate). Test helpers live in `...support` under `src/test`.
- Migrations: V2 `account`, V3 Spring Session tables, V4 `pattern.owner_id`, V5 `pattern_progress`. Flyway owns the schema; `ddl-auto: validate`; `spring.session.jdbc.initialize-schema: never`.
- Emails are normalized with `strip()` and `toLowerCase(Locale.ROOT)` in the request records, before validation. The `account.email` column is UNIQUE and has `CHECK (email = lower(email))`.
- Passwords: `BCryptPasswordEncoder`. Rule for `password`/`newPassword`: ≥ 8 code points and ≤ 72 UTF-8 bytes. Login only requires a non-blank password.
- Login failure: always 401 with detail `Invalid email or password`. Unauthenticated: 401 `Authentication required`. CSRF or access failure: 403 `Access denied`. Email taken: 409 `Email already used`. All responses are `ProblemDetail` (`application/problem+json`).
- Field errors (400) use the existing format: `errors: [{ "field", "message" }]`.
- Another account's pattern, or a pattern that does not exist → 404 `Pattern <id> not found`, never 403.
- Progress positions are 0-based (`stepIndex`, `rowIndex`) and map to the `step_position` / `row_position` columns. Statuses: `IN_PROGRESS`, `FINISHED`. Not started = no row = `progress: null`.
- Timestamps are produced by `vasconcelos.silvio.crochet_back.Timestamps.now()`, truncated to microseconds.
- CSRF uses SPA mode: cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`, required on every POST/PUT/DELETE including register, login and logout.
- Session cookie: `SESSION`, HttpOnly, `SameSite=Lax`, `Secure` taken from `SESSION_COOKIE_SECURE` (default `false`). Idle timeout: `spring.session.timeout: 7d`.
- CORS on `/api/**`: origins from `crochet.cors.allowed-origins`, methods GET/POST/PUT/DELETE, allowed headers `Content-Type` and `X-XSRF-TOKEN`, exposed header `Location`, credentials allowed.
- Tests require Docker. Run Maven from the repo root with `./mvnw` (Git Bash).

## Review Focus

1. A login password longer than 72 UTF-8 bytes must give 401, never a 500 from BCrypt → pinned in Task 2 (`loginWithOverlongPasswordIsUnauthorized`).
2. Logging in with surrounding spaces or different case in the email must find the account → pinned in Task 2 (`loginNormalizesEmail`).
3. Registering while already logged in as another account must switch the session to the new account → pinned in Task 2 (`registerWhileLoggedInSwitchesAccount`).
4. The browser preflight for a PUT that carries `Content-Type` and `X-XSRF-TOKEN` from the front origin must be allowed, with credentials → pinned in Task 2 (`preflightWithCsrfHeaderAllowsCredentials`).
5. An unknown progress status (`"PAUSED"`) must give 400, not 500 → pinned in Task 4 (`unknownStatusIsBadRequest`).

---

## File Structure

```
pom.xml                                                        (modify: deps)
src/main/resources/application.yaml                            (modify)
src/main/resources/db/migration/
  V2__create_account.sql
  V3__create_spring_session_tables.sql
  V4__add_pattern_owner.sql
  V5__create_pattern_progress.sql
src/main/java/vasconcelos/silvio/crochet_back/
  Timestamps.java                         shared µs clock
  account/Account.java                    entity
  account/AccountRepository.java
  account/Emails.java                     normalization
  account/AccountPrincipal.java           UserDetails + CredentialsContainer
  account/AccountUserDetailsService.java
  account/AccountService.java             register, get, changePassword, delete
  account/AccountSessions.java            invalidate stored sessions by principal
  account/EmailAlreadyUsedException.java
  account/AuthController.java             /api/auth/csrf, register, login
  account/MeController.java               /api/me
  account/dto/RegisterRequest, LoginRequest, ChangePasswordRequest,
              DeleteAccountRequest, AccountResponse, BcryptPassword, BcryptPasswordValidator
  pattern/Pattern.java                    (modify: ownerId, progress)
  pattern/PatternProgress.java, ProgressStatus.java, PatternSummaryRow.java
  pattern/PatternRepository.java          (modify)
  pattern/PatternService.java             (modify)
  pattern/PatternController.java          (modify)
  pattern/PatternMapper.java              (modify)
  pattern/ProgressService.java, ProgressController.java, ProgressNotFoundException.java
  pattern/dto/ProgressRequest, ProgressResponse; PatternSummary, PatternResponse (modify)
  config/SecurityConfig.java              (create)
  config/CorsConfig.java                  (rewrite: CorsConfigurationSource bean)
  config/ApiExceptionHandler.java         (modify)
  config/InvalidFieldException.java
src/test/java/vasconcelos/silvio/crochet_back/
  support/TestDatabase.java, TestAccounts.java, Browser.java
  SchemaMigrationTests.java               (modify)
  account/AccountRepositoryTests.java, AuthApiTests.java, AccountManagementApiTests.java
  pattern/PatternApiTests.java, PatternWriteApiTests.java   (modify: authenticated MockMvc)
  pattern/PatternRepositoryTests.java     (modify: owner)
  pattern/PatternOwnershipApiTests.java, ProgressApiTests.java
  pattern/PatternMapperTest.java          (modify)
  config/CorsTests.java                   (modify)
```

---

### Task 1: Account and session schema

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/resources/db/migration/V2__create_account.sql`
- Create: `src/main/resources/db/migration/V3__create_spring_session_tables.sql`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/Timestamps.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/Pattern.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/Account.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/AccountRepository.java`
- Modify: `src/test/java/vasconcelos/silvio/crochet_back/SchemaMigrationTests.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/account/AccountRepositoryTests.java`

**Interfaces:**
- Consumes: `TestcontainersConfiguration` (existing).
- Produces: `Timestamps.now(): Instant`. `Account(String email, String passwordHash)` with `getId()`, `getEmail()`, `getPasswordHash()`, `setPasswordHash(String)` and `getCreatedAt()`. `AccountRepository extends JpaRepository<Account, Long>` with `Optional<Account> findByEmail(String)` and `boolean existsByEmail(String)`. Tables `account`, `spring_session` and `spring_session_attributes`.

- [ ] **Step 1: Add the Spring Session dependency**

In `pom.xml`, after the `spring-boot-starter-flyway` dependency, add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-session-jdbc</artifactId>
        </dependency>
```

- [ ] **Step 2: Configure the session store**

In `src/main/resources/application.yaml`, under `spring:` (same level as `jpa:`), add:

```yaml
  session:
    timeout: 7d
    jdbc:
      initialize-schema: never
```

- [ ] **Step 3: Write the failing tests**

Add this method to `SchemaMigrationTests`:

```java
    @Test
    void flywayCreatesAccountAndSessionTables() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public'
                  and table_name in ('account', 'spring_session', 'spring_session_attributes')
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("account", "spring_session", "spring_session_attributes");
    }
```

Create `src/test/java/vasconcelos/silvio/crochet_back/account/AccountRepositoryTests.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountRepositoryTests {

    @Autowired
    AccountRepository accounts;

    @Test
    void savesAndFindsAccountByEmail() {
        Account saved = accounts.saveAndFlush(new Account("moi@exemple.fr", "hash"));

        Account found = accounts.findByEmail("moi@exemple.fr").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getPasswordHash()).isEqualTo("hash");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(accounts.existsByEmail("moi@exemple.fr")).isTrue();
        assertThat(accounts.existsByEmail("autre@exemple.fr")).isFalse();
    }

    @Test
    void rejectsDuplicateEmail() {
        accounts.saveAndFlush(new Account("moi@exemple.fr", "hash"));

        assertThatThrownBy(() -> accounts.saveAndFlush(new Account("moi@exemple.fr", "hash2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNonLowercaseEmail() {
        assertThatThrownBy(() -> accounts.saveAndFlush(new Account("Moi@Exemple.fr", "hash")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 4: Run them to verify they fail**

Run: `./mvnw test -Dtest='SchemaMigrationTests,AccountRepositoryTests'`
Expected: FAIL — compilation error, `Account` / `AccountRepository` not found.

- [ ] **Step 5: Create `Timestamps` and use it in `Pattern`**

`src/main/java/vasconcelos/silvio/crochet_back/Timestamps.java`:

```java
package vasconcelos.silvio.crochet_back;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Timestamps {

    private Timestamps() {
    }

    /** Current instant at microsecond precision, as stored by PostgreSQL timestamptz. */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
```

In `Pattern.java`: remove `import java.time.temporal.ChronoUnit;`, add `import vasconcelos.silvio.crochet_back.Timestamps;`, delete the private `now()` method and its Javadoc, and replace the two calls to `now()` with `Timestamps.now()`.

- [ ] **Step 6: Write the migrations**

`src/main/resources/db/migration/V2__create_account.sql`:

```sql
CREATE TABLE account
(
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_account_email UNIQUE (email),
    CONSTRAINT ck_account_email_lowercase CHECK (email = lower(email))
);
```

`src/main/resources/db/migration/V3__create_spring_session_tables.sql`. This is the official `org/springframework/session/jdbc/schema-postgresql.sql` from spring-session-jdbc 4.1.1:

```sql
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
```

Unquoted identifiers are folded to lowercase by PostgreSQL, so the tables are `spring_session` and `spring_session_attributes`.

- [ ] **Step 7: Create `Account` and `AccountRepository`**

`src/main/java/vasconcelos/silvio/crochet_back/account/Account.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vasconcelos.silvio.crochet_back.Timestamps;

import java.time.Instant;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254)
    private String email;

    @Setter
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** {@code email} must already be normalized (see {@link Emails#normalize}). */
    public Account(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void onCreate() {
        createdAt = Timestamps.now();
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/AccountRepository.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

The Javadoc in `Account` references `Emails`, which Task 2 creates. A Javadoc `{@link}` to a missing class does not break compilation, but write it now anyway so the reference resolves after Task 2.

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: PASS — all existing tests plus the 4 new ones.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/resources src/main/java/vasconcelos/silvio/crochet_back src/test/java/vasconcelos/silvio/crochet_back
git commit -m "feat: add account and spring session schema"
```

---

### Task 2: Security, registration, login, logout and /api/me

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/Emails.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/AccountPrincipal.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/AccountUserDetailsService.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/EmailAlreadyUsedException.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/AccountService.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/AuthController.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/MeController.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/dto/RegisterRequest.java`, `LoginRequest.java`, `AccountResponse.java`, `BcryptPassword.java`, `BcryptPasswordValidator.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/config/SecurityConfig.java`
- Rewrite: `src/main/java/vasconcelos/silvio/crochet_back/config/CorsConfig.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/config/ApiExceptionHandler.java`
- Create: `src/test/java/vasconcelos/silvio/crochet_back/support/TestDatabase.java`, `TestAccounts.java`, `Browser.java`
- Modify: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternApiTests.java`, `PatternWriteApiTests.java`
- Modify: `src/test/java/vasconcelos/silvio/crochet_back/config/CorsTests.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/account/AuthApiTests.java`

**Interfaces:**
- Consumes: `Account`, `AccountRepository`, `Timestamps` (Task 1); `CorsProperties` (existing record `CorsProperties(List<String> allowedOrigins)`).
- Produces:
  - `Emails.normalize(String): String` (null-safe).
  - `AccountPrincipal(Long id, String email, String passwordHash)`, with `Long id()`, `getUsername()` (= email), `getPassword()` and `eraseCredentials()`.
  - `AccountService`: `Account register(String email, String rawPassword)` (throws `EmailAlreadyUsedException`) and `Account get(long id)` (throws `InsufficientAuthenticationException` if the account is gone).
  - `record AccountResponse(Long id, String email, Instant createdAt)` with `static AccountResponse from(Account)`.
  - `@BcryptPassword` constraint.
  - Beans: `SecurityFilterChain`, `PasswordEncoder` (BCrypt), `AuthenticationManager`, `SecurityContextRepository`, `CorsConfigurationSource`.
  - Test support: `TestDatabase.reset(JdbcTemplate)`, `TestAccounts.PASSWORD`, `TestAccounts.create(AccountRepository, PasswordEncoder, String email): AccountPrincipal`, `TestAccounts.mockMvcAs(WebApplicationContext, AccountPrincipal): MockMvc`, and `Browser(MockMvc)` with `perform(...)`, `fetchCsrf()`, `cookie(String)`.

- [ ] **Step 1: Add the security dependencies**

In `pom.xml`, after `spring-boot-starter-session-jdbc`, add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
```

After `spring-boot-starter-webmvc-test`, add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Configure the session cookie**

Append to `src/main/resources/application.yaml` (top level):

```yaml
server:
  servlet:
    session:
      cookie:
        http-only: true
        same-site: lax
        secure: ${SESSION_COOKIE_SECURE:false}
```

- [ ] **Step 3: Create the test support classes**

`src/test/java/vasconcelos/silvio/crochet_back/support/TestDatabase.java`:

```java
package vasconcelos.silvio.crochet_back.support;

import org.springframework.jdbc.core.JdbcTemplate;

public final class TestDatabase {

    private TestDatabase() {
    }

    /** Empties every application table and the session store. */
    public static void reset(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE account, pattern, spring_session RESTART IDENTITY CASCADE");
    }
}
```

`src/test/java/vasconcelos/silvio/crochet_back/support/TestAccounts.java`:

```java
package vasconcelos.silvio.crochet_back.support;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.account.Account;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.account.AccountRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

public final class TestAccounts {

    public static final String PASSWORD = "motdepasse-secret";

    private TestAccounts() {
    }

    /** Inserts a real account row and returns the principal Spring Security builds for it. */
    public static AccountPrincipal create(AccountRepository accounts, PasswordEncoder encoder, String email) {
        Account account = accounts.save(new Account(email, encoder.encode(PASSWORD)));
        return new AccountPrincipal(account.getId(), account.getEmail(), account.getPasswordHash());
    }

    /** MockMvc whose every request is authenticated as {@code principal} and carries a valid CSRF token. */
    public static MockMvc mockMvcAs(WebApplicationContext context, AccountPrincipal principal) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user(principal)).with(csrf()))
                .build();
    }
}
```

`src/test/java/vasconcelos/silvio/crochet_back/support/Browser.java`:

```java
package vasconcelos.silvio.crochet_back.support;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real session + CSRF flow: keeps cookies between requests like a browser and echoes the
 * {@code XSRF-TOKEN} cookie in the {@code X-XSRF-TOKEN} header like the SPA front will.
 */
public final class Browser {

    private final MockMvc mvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    public Browser(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        if (!cookies.isEmpty()) {
            request.cookie(cookies.values().toArray(Cookie[]::new));
        }
        Cookie xsrf = cookies.get("XSRF-TOKEN");
        if (xsrf != null) {
            request.header("X-XSRF-TOKEN", xsrf.getValue());
        }
        ResultActions result = mvc.perform(request);
        for (Cookie cookie : result.andReturn().getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie);
            }
        }
        return result;
    }

    /** Fetches a CSRF token, as the front does before its first POST. */
    public Browser fetchCsrf() throws Exception {
        perform(get("/api/auth/csrf")).andExpect(status().isNoContent());
        return this;
    }

    public Cookie cookie(String name) {
        return cookies.get(name);
    }
}
```

- [ ] **Step 4: Write the failing auth tests**

`src/test/java/vasconcelos/silvio/crochet_back/account/AuthApiTests.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.Browser;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTests {

    static final String PASSWORD = "motdepasse-secret";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        TestDatabase.reset(jdbc);
    }

    static String credentials(String email, String password) {
        return """
                { "email": "%s", "password": "%s" }""".formatted(email, password);
    }

    Browser registered(String email) throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(credentials(email, PASSWORD)))
                .andExpect(status().isCreated());
        return browser;
    }

    @Test
    void registerCreatesAccountAndOpensSession() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();

        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("Moi@Exemple.fr", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(notNullValue()))
                .andExpect(jsonPath("$.email").value("moi@exemple.fr"))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        browser.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("moi@exemple.fr"));
    }

    @Test
    void sessionIsStoredInDatabaseWithHttpOnlyLaxCookie() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();

        MvcResult result = browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(header -> assertThat(header)
                        .startsWith("SESSION=")
                        .contains("HttpOnly")
                        .contains("SameSite=Lax"));
        Integer stored = jdbc.queryForObject(
                "select count(*) from spring_session where principal_name = 'moi@exemple.fr'", Integer.class);
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void registerWithTakenEmailIgnoringCaseIsConflict() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("  MOI@exemple.FR ", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Email already used"));
    }

    static Stream<Arguments> invalidRegistrations() {
        return Stream.of(
                Arguments.of(credentials("pas-un-email", PASSWORD), "email"),
                Arguments.of(credentials(" ", PASSWORD), "email"),
                Arguments.of(credentials("a".repeat(250) + "@x.fr", PASSWORD), "email"),
                Arguments.of(credentials("moi@exemple.fr", "1234567"), "password"),
                Arguments.of(credentials("moi@exemple.fr", "é".repeat(37)), "password"),
                Arguments.of("{ \"email\": \"moi@exemple.fr\" }", "password"));
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrations")
    void invalidRegistrationIsBadRequestNamingTheField(String json, String field) throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));

        assertThat(jdbc.queryForObject("select count(*) from account", Integer.class)).isZero();
    }

    @Test
    void registerAcceptsPasswordOfExactly72Bytes() throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", "é".repeat(36))))
                .andExpect(status().isCreated());
    }

    @Test
    void registerWhileLoggedInSwitchesAccount() throws Exception {
        Browser browser = registered("premier@exemple.fr");

        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                        .content(credentials("second@exemple.fr", PASSWORD)))
                .andExpect(status().isCreated());

        browser.perform(get("/api/me")).andExpect(jsonPath("$.email").value("second@exemple.fr"));
    }

    @Test
    void loginOpensSession() throws Exception {
        registered("moi@exemple.fr");
        Browser browser = new Browser(mvc).fetchCsrf();

        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("moi@exemple.fr"));

        browser.perform(get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void loginNormalizesEmail() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("  Moi@EXEMPLE.fr ", PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void loginFailureIsIdenticalForWrongPasswordAndUnknownEmail() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", "mauvais-mot-de-passe")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("inconnu@exemple.fr", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void loginWithOverlongPasswordIsUnauthorized() throws Exception {
        registered("moi@exemple.fr");

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", "é".repeat(60))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithMissingFieldIsBadRequest() throws Exception {
        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("{ \"email\": \"moi@exemple.fr\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void loginChangesSessionId() throws Exception {
        Browser browser = registered("moi@exemple.fr");
        Cookie before = browser.cookie("SESSION");

        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isOk());

        assertThat(browser.cookie("SESSION").getValue()).isNotEqualTo(before.getValue());
        mvc.perform(get("/api/me").cookie(before)).andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content(credentials("moi@exemple.fr", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @Test
    void protectedEndpointsWithoutSessionAreUnauthorized() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required"));
        mvc.perform(get("/api/patterns")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutInvalidatesSessionServerSide() throws Exception {
        Browser browser = registered("moi@exemple.fr");
        Cookie session = browser.cookie("SESSION");

        browser.perform(post("/api/auth/logout")).andExpect(status().isNoContent());

        mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
    }
}
```

- [ ] **Step 5: Make the existing pattern API tests authenticate**

In both `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternApiTests.java` and `PatternWriteApiTests.java`:

1. Delete the `@AutoConfigureMockMvc` annotation and its import `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
2. Replace

```java
    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE pattern RESTART IDENTITY CASCADE");
    }
```

with

```java
    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        mvc = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "moi@exemple.fr"));
    }
```

3. Add these imports:

```java
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;
```

No test method changes.

- [ ] **Step 6: Update `CorsTests`**

In `src/test/java/vasconcelos/silvio/crochet_back/config/CorsTests.java`, replace the whole `locationHeaderIsExposedToConfiguredOrigin` method with these two methods:

```java
    @Test
    void locationHeaderIsExposedToConfiguredOrigin() throws Exception {
        mvc.perform(get("/api/auth/csrf").header("Origin", "http://localhost:5173"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Location")))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflightWithCsrfHeaderAllowsCredentials() throws Exception {
        mvc.perform(options("/api/patterns/1")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "content-type,x-xsrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("x-xsrf-token")));
    }
```

- [ ] **Step 7: Run the auth tests to verify they fail**

Run: `./mvnw test -Dtest=AuthApiTests`
Expected: FAIL — compilation errors (`AccountPrincipal` not found in `TestAccounts`).

- [ ] **Step 8: Create the account support classes**

`src/main/java/vasconcelos/silvio/crochet_back/account/Emails.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import java.util.Locale;

public final class Emails {

    private Emails() {
    }

    /** Login identifier form: surrounding whitespace removed, lowercase. {@code null} stays {@code null}. */
    public static String normalize(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/AccountPrincipal.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/** Authenticated account stored in the session; the password hash is erased after login. */
public final class AccountPrincipal implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private String passwordHash;

    public AccountPrincipal(Long id, String email, String passwordHash) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long id() {
        return id;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/AccountUserDetailsService.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccountUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    @Override
    public UserDetails loadUserByUsername(String username) {
        return accounts.findByEmail(Emails.normalize(username))
                .map(account -> new AccountPrincipal(account.getId(), account.getEmail(), account.getPasswordHash()))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/EmailAlreadyUsedException.java`:

```java
package vasconcelos.silvio.crochet_back.account;

public class EmailAlreadyUsedException extends RuntimeException {

    public EmailAlreadyUsedException() {
        super("Email already used");
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/AccountService.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    /** {@code email} must already be normalized. */
    @Transactional
    public Account register(String email, String rawPassword) {
        if (accounts.existsByEmail(email)) {
            throw new EmailAlreadyUsedException();
        }
        try {
            return accounts.saveAndFlush(new Account(email, passwordEncoder.encode(rawPassword)));
        } catch (DataIntegrityViolationException concurrentRegistration) {
            throw new EmailAlreadyUsedException();
        }
    }

    /** Loads the logged-in account; a session outliving its account is treated as unauthenticated. */
    public Account get(long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new InsufficientAuthenticationException("Account no longer exists"));
    }
}
```

- [ ] **Step 9: Create the DTOs and the password constraint**

`src/main/java/vasconcelos/silvio/crochet_back/account/dto/BcryptPassword.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** At least 8 characters and at most 72 UTF-8 bytes (BCrypt's input limit). {@code null} is valid. */
@Documented
@Constraint(validatedBy = BcryptPasswordValidator.class)
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE, TYPE_USE})
public @interface BcryptPassword {

    String message() default "must be at least 8 characters and at most 72 bytes in UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/dto/BcryptPasswordValidator.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class BcryptPasswordValidator implements ConstraintValidator<BcryptPassword, String> {

    static final int MIN_CHARACTERS = 8;
    static final int MAX_BYTES = 72;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.codePointCount(0, value.length()) >= MIN_CHARACTERS
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/dto/RegisterRequest.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vasconcelos.silvio.crochet_back.account.Emails;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @BcryptPassword String password) {

    public RegisterRequest {
        email = Emails.normalize(email);
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/dto/LoginRequest.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;
import vasconcelos.silvio.crochet_back.account.Emails;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {

    public LoginRequest {
        email = Emails.normalize(email);
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/dto/AccountResponse.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import vasconcelos.silvio.crochet_back.account.Account;

import java.time.Instant;

public record AccountResponse(Long id, String email, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getEmail(), account.getCreatedAt());
    }
}
```

- [ ] **Step 10: Create the controllers**

`src/main/java/vasconcelos/silvio/crochet_back/account/AuthController.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.dto.AccountResponse;
import vasconcelos.silvio.crochet_back.account.dto.LoginRequest;
import vasconcelos.silvio.crochet_back.account.dto.RegisterRequest;

/** Logout is handled by Spring Security's logout filter (see SecurityConfig). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accountService;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;

    @GetMapping("/csrf")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void csrf(CsrfToken token) {
        // Reading the deferred token makes the repository write the XSRF-TOKEN cookie.
        token.getToken();
    }

    @PostMapping("/register")
    public ResponseEntity<AccountResponse> register(
            @Valid @RequestBody RegisterRequest body, HttpServletRequest request, HttpServletResponse response) {
        accountService.register(body.email(), body.password());
        AccountPrincipal principal = signIn(body.email(), body.password(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(accountService.get(principal.id())));
    }

    @PostMapping("/login")
    public AccountResponse login(
            @Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        AccountPrincipal principal = signIn(body.email(), body.password(), request, response);
        return AccountResponse.from(accountService.get(principal.id()));
    }

    private AccountPrincipal signIn(
            String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        if (request.getSession(false) != null) {
            request.changeSessionId(); // session fixation protection
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return (AccountPrincipal) authentication.getPrincipal();
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/MeController.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.dto.AccountResponse;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountService accountService;

    @GetMapping
    public AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return AccountResponse.from(accountService.get(principal.id()));
    }
}
```

- [ ] **Step 11: Create `SecurityConfig` and rewrite `CorsConfig`**

`src/main/java/vasconcelos/silvio/crochet_back/config/SecurityConfig.java`:

```java
package vasconcelos.silvio.crochet_back.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
public class SecurityConfig {

    /**
     * 401 and 403 are delegated to the MVC exception resolver so that ApiExceptionHandler renders
     * them as ProblemDetail like every other error.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.spa())
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // an API has no login page to return to: without this, every anonymous 401 would
                // create a session just to remember the request
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
```

Replace the whole content of `src/main/java/vasconcelos/silvio/crochet_back/config/CorsConfig.java` with:

```java
package vasconcelos.silvio.crochet_back.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/** Picked up by Spring Security's CORS filter, so preflights are answered before authentication. */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        config.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, "X-XSRF-TOKEN"));
        config.setExposedHeaders(List.of(HttpHeaders.LOCATION));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
```

- [ ] **Step 12: Map the new errors in `ApiExceptionHandler`**

Add these imports:

```java
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import vasconcelos.silvio.crochet_back.account.EmailAlreadyUsedException;
```

Add these methods after `handleNotFound`:

```java
    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentials() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleUnauthenticated() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    ProblemDetail handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }
```

Spring picks the most specific `@ExceptionHandler`, so `BadCredentialsException` wins over `AuthenticationException`. CSRF failures (`MissingCsrfTokenException`, `InvalidCsrfTokenException`) are `AccessDeniedException`s.

- [ ] **Step 13: Run the full suite**

Run: `./mvnw test`
Expected: PASS — every test, including `AuthApiTests`, the authenticated pattern API tests and the updated `CorsTests`.

If `loginWithOverlongPasswordIsUnauthorized` fails with a 500 caused by `IllegalArgumentException: password cannot be more than 72 bytes`, BCrypt is rejecting the long password at `matches`. Fix it in `LoginRequest` by adding `@Size(max = 72)` on the password, and add a field check in `AuthController.login` that throws `BadCredentialsException` when `body.password().getBytes(UTF_8).length > 72`. Keep the response a 401, not a 400, so the failure is indistinguishable from a wrong password. Record this as a ruling.

- [ ] **Step 14: Commit**

```bash
git add pom.xml src/main src/test
git commit -m "feat: add session-based authentication with registration and login"
```

---

### Task 3: Pattern ownership

**Files:**
- Create: `src/main/resources/db/migration/V4__add_pattern_owner.sql`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/Pattern.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternRepository.java`
- Rewrite: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternService.java`
- Rewrite: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternController.java`
- Rewrite: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternRepositoryTests.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternOwnershipApiTests.java`

**Interfaces:**
- Consumes: `AccountPrincipal.id()`, `TestAccounts`, `TestDatabase`, `Account`, `AccountRepository` (Tasks 1–2); `PatternJson.CHAT`, `PatternJson.create` (existing).
- Produces:
  - `Pattern.getOwnerId()` / `setOwnerId(Long)`.
  - `PatternRepository.findByIdAndOwnerId(Long id, Long ownerId): Optional<Pattern>`, `findAllSummaries(Long ownerId): List<PatternSummary>`.
  - `PatternService`: `list(long ownerId)`, `get(long ownerId, long id)`, `create(long ownerId, PatternRequest)`, `replace(long ownerId, long id, PatternRequest)`, `delete(long ownerId, long id)`, public `Pattern find(long ownerId, long id)` (used by `ProgressService` in Task 4).

- [ ] **Step 1: Write the failing ownership tests**

`src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternOwnershipApiTests.java`:

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
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
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
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.titled;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PatternOwnershipApiTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    AccountPrincipal alice;
    MockMvc aliceMvc;
    MockMvc bobMvc;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(jdbc);
        alice = TestAccounts.create(accounts, passwordEncoder, "alice@exemple.fr");
        aliceMvc = TestAccounts.mockMvcAs(context, alice);
        bobMvc = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "bob@exemple.fr"));
    }

    @Test
    void createdPatternBelongsToTheLoggedInAccount() throws Exception {
        String location = create(aliceMvc, CHAT);
        long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        Long ownerId = jdbc.queryForObject("select owner_id from pattern where id = ?", Long.class, id);
        assertThat(ownerId).isEqualTo(alice.id());
    }

    @Test
    void listOnlyContainsOwnPatterns() throws Exception {
        create(aliceMvc, CHAT);
        create(bobMvc, titled("Bonnet"));

        aliceMvc.perform(get("/api/patterns"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Amigurumi Chat"));
        bobMvc.perform(get("/api/patterns"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Bonnet"));
    }

    @Test
    void otherAccountsPatternIsNotFoundForEveryOperation() throws Exception {
        String location = create(aliceMvc, CHAT);

        bobMvc.perform(get(location)).andExpect(status().isNotFound());
        bobMvc.perform(put(location).contentType(APPLICATION_JSON).content(titled("Volé")))
                .andExpect(status().isNotFound());
        bobMvc.perform(delete(location)).andExpect(status().isNotFound());

        aliceMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"));
    }
}
```

Rewrite `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternRepositoryTests.java` so every pattern has an owner:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.Account;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PatternRepositoryTests {

    @Autowired
    PatternRepository repository;

    @Autowired
    AccountRepository accounts;

    @Autowired
    EntityManager em;

    Long ownerId;

    @BeforeEach
    void createOwner() {
        ownerId = accounts.save(new Account("moi@exemple.fr", "hash")).getId();
    }

    @Test
    void persistsStepsAndRowsOrderedByPosition() {
        Pattern pattern = new Pattern();
        pattern.setTitle("Chat");
        pattern.setOwnerId(ownerId);
        Step head = new Step(0, "Tête");
        head.addRow(new PatternRow(1, "2", "6aug (12)"));
        head.addRow(new PatternRow(0, "1", "6ms (6)"));
        Step body = new Step(1, "Corps");
        body.addRow(new PatternRow(0, "1-5", "12ms (12)"));
        // inserted out of order on purpose: reading must sort by position
        pattern.addStep(body);
        pattern.addStep(head);

        Long id = repository.save(pattern).getId();
        em.flush();
        em.clear();

        Pattern loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getSteps()).extracting(Step::getTitle).containsExactly("Tête", "Corps");
        assertThat(loaded.getSteps().getFirst().getRows())
                .extracting(PatternRow::getValue)
                .containsExactly("6ms (6)", "6aug (12)");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
    }

    @Test
    void findAllSummariesSortsByTitleIgnoringCaseAndCountsSteps() {
        repository.save(pattern("zèbre", 1));
        repository.save(pattern("Chat", 2));
        repository.save(pattern("abeille", 3));

        List<PatternSummary> summaries = repository.findAllSummaries(ownerId);

        assertThat(summaries).extracting(PatternSummary::title).containsExactly("abeille", "Chat", "zèbre");
        assertThat(summaries).extracting(PatternSummary::stepCount).containsExactly(3, 2, 1);
    }

    @Test
    void findAllSummariesBreaksTitleTiesById() {
        Pattern first = repository.save(pattern("Bonnet", 1));
        Pattern second = repository.save(pattern("bonnet", 1));
        // updating the first row rewrites its tuple after the second one in the heap,
        // so an order relying on physical position would return second before first
        first.setDescription("modifié");
        em.flush();

        List<PatternSummary> summaries = repository.findAllSummaries(ownerId);

        assertThat(summaries).extracting(PatternSummary::id).containsExactly(first.getId(), second.getId());
    }

    @Test
    void findAllSummariesAndFindByIdAreScopedToTheOwner() {
        Long otherOwner = accounts.save(new Account("autre@exemple.fr", "hash")).getId();
        Pattern mine = repository.save(pattern("Mien", 1));
        Pattern theirs = pattern("Sien", 1);
        theirs.setOwnerId(otherOwner);
        repository.save(theirs);

        assertThat(repository.findAllSummaries(ownerId)).extracting(PatternSummary::title).containsExactly("Mien");
        assertThat(repository.findByIdAndOwnerId(mine.getId(), ownerId)).isPresent();
        assertThat(repository.findByIdAndOwnerId(theirs.getId(), ownerId)).isEmpty();
    }

    private Pattern pattern(String title, int stepCount) {
        Pattern pattern = new Pattern();
        pattern.setTitle(title);
        pattern.setOwnerId(ownerId);
        for (int i = 0; i < stepCount; i++) {
            Step step = new Step(i, "Étape " + i);
            step.addRow(new PatternRow(0, "1", "6ms"));
            pattern.addStep(step);
        }
        return pattern;
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='PatternOwnershipApiTests,PatternRepositoryTests'`
Expected: FAIL — compilation errors (`setOwnerId`, `findAllSummaries(Long)` and `findByIdAndOwnerId` not found).

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V4__add_pattern_owner.sql`:

```sql
-- Development patterns have no owner and are discarded (accounts spec, section 2).
DELETE FROM pattern;

ALTER TABLE pattern
    ADD COLUMN owner_id BIGINT NOT NULL REFERENCES account (id) ON DELETE CASCADE;

CREATE INDEX ix_pattern_owner_id ON pattern (owner_id);
```

- [ ] **Step 4: Add `ownerId` to `Pattern`**

In `Pattern.java`, add after the `id` field:

```java
    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;
```

(The class-level `@Getter`/`@Setter` provide `getOwnerId()`/`setOwnerId(Long)`.)

- [ ] **Step 5: Scope the repository**

Replace the body of `PatternRepository` with:

```java
public interface PatternRepository extends JpaRepository<Pattern, Long> {

    Optional<Pattern> findByIdAndOwnerId(Long id, Long ownerId);

    @Query("""
            select new vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps))
            from Pattern p
            where p.ownerId = :ownerId
            order by lower(p.title), p.id
            """)
    List<PatternSummary> findAllSummaries(@Param("ownerId") Long ownerId);
}
```

Add the imports `java.util.Optional` and `org.springframework.data.repository.query.Param`.

- [ ] **Step 6: Rewrite `PatternService`**

```java
package vasconcelos.silvio.crochet_back.pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PatternService {

    private final PatternRepository repository;
    private final PatternMapper mapper;

    public List<PatternSummary> list(long ownerId) {
        return repository.findAllSummaries(ownerId);
    }

    public PatternResponse get(long ownerId, long id) {
        return mapper.toResponse(find(ownerId, id));
    }

    @Transactional
    public PatternResponse create(long ownerId, PatternRequest request) {
        Pattern pattern = mapper.toEntity(request);
        pattern.setOwnerId(ownerId);
        return mapper.toResponse(repository.save(pattern));
    }

    @Transactional
    public PatternResponse replace(long ownerId, long id, PatternRequest request) {
        Pattern pattern = find(ownerId, id);
        mapper.applyFields(request, pattern);
        // Hibernate flushes inserts before orphan deletes; removing old steps first avoids
        // violating UNIQUE(pattern_id, position) when new steps reuse the same positions.
        pattern.getSteps().clear();
        repository.flush();
        mapper.toSteps(request.steps()).forEach(pattern::addStep);
        pattern.touch();
        repository.flush();
        return mapper.toResponse(pattern);
    }

    @Transactional
    public void delete(long ownerId, long id) {
        repository.delete(find(ownerId, id));
    }

    /**
     * Another account's pattern is reported exactly like a missing one. Public: it is called through
     * the Spring proxy by ProgressService, and a non-public method on a CGLIB proxy is not a safe call.
     */
    public Pattern find(long ownerId, long id) {
        return repository.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new PatternNotFoundException(id));
    }
}
```

- [ ] **Step 7: Rewrite `PatternController`**

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/patterns")
@RequiredArgsConstructor
public class PatternController {

    private final PatternService service;

    @GetMapping
    public List<PatternSummary> list(@AuthenticationPrincipal AccountPrincipal principal) {
        return service.list(principal.id());
    }

    @GetMapping("/{id}")
    public PatternResponse get(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long id) {
        return service.get(principal.id(), id);
    }

    @PostMapping
    public ResponseEntity<PatternResponse> create(
            @AuthenticationPrincipal AccountPrincipal principal, @Valid @RequestBody PatternRequest request) {
        PatternResponse created = service.create(principal.id(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public PatternResponse replace(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody PatternRequest request) {
        return service.replace(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long id) {
        service.delete(principal.id(), id);
    }
}
```

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "feat: make patterns private to their owner"
```

---

### Task 4: Progress endpoints

**Files:**
- Create: `src/main/resources/db/migration/V5__create_pattern_progress.sql`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressStatus.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternProgress.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/Pattern.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/ProgressRequest.java`, `ProgressResponse.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternMapper.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressNotFoundException.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressService.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressController.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/config/InvalidFieldException.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/config/ApiExceptionHandler.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/ProgressApiTests.java`

**Interfaces:**
- Consumes: `PatternService.find(long ownerId, long id)` (Task 3), `AccountPrincipal.id()`, `TestAccounts`, `TestDatabase`, `PatternJson.CHAT`, `PatternJson.create`, `Timestamps.now()`.
- Produces:
  - `enum ProgressStatus { IN_PROGRESS, FINISHED }`.
  - `PatternProgress` with getters `getStatus()`, `getStepPosition()`, `getRowPosition()`, `getStartedAt()`, `getUpdatedAt()` and `getFinishedAt()`, plus package-private `update(ProgressStatus, int, int)`.
  - `Pattern.getProgress()`, `Pattern.updateProgress(ProgressStatus, int, int): PatternProgress` and `Pattern.clearProgress()`.
  - `record ProgressRequest(ProgressStatus status, Integer stepIndex, Integer rowIndex)`.
  - `record ProgressResponse(ProgressStatus status, int stepIndex, int rowIndex, Instant startedAt, Instant updatedAt, Instant finishedAt)`.
  - `PatternMapper.toProgressResponse(PatternProgress): ProgressResponse` (null-safe).
  - `InvalidFieldException(String field, String message)` → 400 in the `errors` format.
  - `ProgressNotFoundException(long patternId)` → 404 `Pattern <id> is not started`.
  - `PUT` and `DELETE /api/patterns/{id}/progress`.

- [ ] **Step 1: Write the failing progress tests**

`src/test/java/vasconcelos/silvio/crochet_back/pattern/ProgressApiTests.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.account.AccountRepository;
import vasconcelos.silvio.crochet_back.support.TestAccounts;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProgressApiTests {

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountRepository accounts;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    String location;

    @BeforeEach
    void setUp() throws Exception {
        TestDatabase.reset(jdbc);
        mvc = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "moi@exemple.fr"));
        location = create(mvc, CHAT); // steps: "Tête" (3 rows), "Corps" (1 row)
    }

    static String progress(String status, Object stepIndex, Object rowIndex) {
        return """
                { "status": %s, "stepIndex": %s, "rowIndex": %s }"""
                .formatted(status == null ? "null" : "\"" + status + "\"", stepIndex, rowIndex);
    }

    ResultActions putProgress(MockMvc client, String json) throws Exception {
        return client.perform(put(location + "/progress").contentType(APPLICATION_JSON).content(json));
    }

    String putProgressOk(String json) throws Exception {
        return putProgress(mvc, json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    static Instant instant(String json, String path) {
        return Instant.parse(JsonPath.read(json, path));
    }

    @Test
    void startCreatesProgressAtGivenPosition() throws Exception {
        putProgress(mvc, progress("IN_PROGRESS", 0, 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.stepIndex").value(0))
                .andExpect(jsonPath("$.rowIndex").value(0))
                .andExpect(jsonPath("$.startedAt").value(notNullValue()))
                .andExpect(jsonPath("$.updatedAt").value(notNullValue()))
                .andExpect(jsonPath("$.finishedAt").doesNotExist());

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isEqualTo(1);
    }

    @Test
    void advancingKeepsStartedAtAndBumpsUpdatedAt() throws Exception {
        String started = putProgressOk(progress("IN_PROGRESS", 0, 0));

        String advanced = putProgressOk(progress("IN_PROGRESS", 0, 2));

        assertThat((Integer) JsonPath.read(advanced, "$.rowIndex")).isEqualTo(2);
        assertThat(instant(advanced, "$.startedAt")).isEqualTo(instant(started, "$.startedAt"));
        assertThat(instant(advanced, "$.updatedAt")).isAfter(instant(started, "$.updatedAt"));
    }

    @Test
    void finishingSetsFinishedAtOnceAndReopeningClearsIt() throws Exception {
        String started = putProgressOk(progress("IN_PROGRESS", 0, 0));

        String finished = putProgressOk(progress("FINISHED", 1, 0));
        String finishedAgain = putProgressOk(progress("FINISHED", 1, 0));
        String reopened = putProgressOk(progress("IN_PROGRESS", 1, 0));

        assertThat((String) JsonPath.read(finished, "$.status")).isEqualTo("FINISHED");
        assertThat(instant(finishedAgain, "$.finishedAt")).isEqualTo(instant(finished, "$.finishedAt"));
        assertThat((String) JsonPath.read(reopened, "$.status")).isEqualTo("IN_PROGRESS");
        assertThat((Object) JsonPath.read(reopened, "$.finishedAt")).isNull();
        assertThat(instant(reopened, "$.startedAt")).isEqualTo(instant(started, "$.startedAt"));
    }

    @Test
    void abandonDeletesProgressThenReportsNotStarted() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));

        mvc.perform(delete(location + "/progress")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isZero();
        mvc.perform(delete(location + "/progress"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("is not started")));
    }

    static Stream<Arguments> invalidProgress() {
        return Stream.of(
                Arguments.of(progress("IN_PROGRESS", 2, 0), "stepIndex"),
                Arguments.of(progress("IN_PROGRESS", -1, 0), "stepIndex"),
                Arguments.of(progress("IN_PROGRESS", 0, 3), "rowIndex"),
                Arguments.of(progress("IN_PROGRESS", 1, 1), "rowIndex"),
                Arguments.of(progress("IN_PROGRESS", 0, -1), "rowIndex"),
                Arguments.of(progress(null, 0, 0), "status"),
                Arguments.of(progress("IN_PROGRESS", "null", 0), "stepIndex"),
                Arguments.of(progress("IN_PROGRESS", 0, "null"), "rowIndex"));
    }

    @ParameterizedTest
    @MethodSource("invalidProgress")
    void invalidProgressIsBadRequestNamingTheField(String json, String field) throws Exception {
        putProgress(mvc, json)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isZero();
    }

    @Test
    void unknownStatusIsBadRequest() throws Exception {
        putProgress(mvc, progress("PAUSED", 0, 0))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unknownPatternIsNotFound() throws Exception {
        mvc.perform(put("/api/patterns/999/progress").contentType(APPLICATION_JSON)
                        .content(progress("IN_PROGRESS", 0, 0)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/patterns/999/progress")).andExpect(status().isNotFound());
    }

    @Test
    void otherAccountCannotTouchProgress() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));
        MockMvc bob = TestAccounts.mockMvcAs(context, TestAccounts.create(accounts, passwordEncoder, "bob@exemple.fr"));

        putProgress(bob, progress("FINISHED", 1, 0)).andExpect(status().isNotFound());
        bob.perform(delete(location + "/progress")).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select status from pattern_progress", String.class)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void deletingPatternDeletesItsProgress() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));

        mvc.perform(delete(location)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from pattern_progress", Integer.class)).isZero();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=ProgressApiTests`
Expected: FAIL. Every `PUT .../progress` returns 405 (only `/api/patterns/{id}` has a PUT) or 404, so the status assertions fail.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V5__create_pattern_progress.sql`:

```sql
CREATE TABLE pattern_progress
(
    pattern_id    BIGINT PRIMARY KEY REFERENCES pattern (id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL,
    step_position INT         NOT NULL,
    row_position  INT         NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    finished_at   TIMESTAMPTZ,
    CONSTRAINT ck_progress_status CHECK (status IN ('IN_PROGRESS', 'FINISHED')),
    CONSTRAINT ck_progress_step_position CHECK (step_position >= 0),
    CONSTRAINT ck_progress_row_position CHECK (row_position >= 0),
    CONSTRAINT ck_progress_finished_at CHECK ((status = 'FINISHED') = (finished_at IS NOT NULL))
);
```

- [ ] **Step 4: Create the progress model**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressStatus.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

public enum ProgressStatus {
    IN_PROGRESS,
    FINISHED
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternProgress.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vasconcelos.silvio.crochet_back.Timestamps;

import java.time.Instant;

/** Current step and row of a started pattern; positions are 0-based like Step/PatternRow positions. */
@Entity
@Table(name = "pattern_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternProgress {

    @Id
    @Column(name = "pattern_id")
    private Long patternId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pattern_id")
    private Pattern pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProgressStatus status;

    @Column(name = "step_position", nullable = false)
    private int stepPosition;

    @Column(name = "row_position", nullable = false)
    private int rowPosition;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    PatternProgress(Pattern pattern) {
        this.pattern = pattern;
        this.startedAt = Timestamps.now();
    }

    /** finishedAt is set on the transition to FINISHED only, and cleared when reopened. */
    void update(ProgressStatus newStatus, int newStepPosition, int newRowPosition) {
        if (newStatus == ProgressStatus.FINISHED && status != ProgressStatus.FINISHED) {
            finishedAt = Timestamps.now();
        } else if (newStatus == ProgressStatus.IN_PROGRESS) {
            finishedAt = null;
        }
        status = newStatus;
        stepPosition = newStepPosition;
        rowPosition = newRowPosition;
        updatedAt = Timestamps.now();
    }
}
```

In `Pattern.java`, add imports `jakarta.persistence.FetchType` and `jakarta.persistence.OneToOne`, then add after the `steps` field:

```java
    @OneToOne(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Setter(AccessLevel.NONE)
    private PatternProgress progress;
```

and after `addStep`:

```java
    /** Starts the pattern if needed, then moves its progress. Bounds are checked by the caller. */
    public PatternProgress updateProgress(ProgressStatus status, int stepPosition, int rowPosition) {
        if (progress == null) {
            progress = new PatternProgress(this);
        }
        progress.update(status, stepPosition, rowPosition);
        return progress;
    }

    /** Abandons the pattern: its progress row is deleted (orphan removal). */
    public void clearProgress() {
        progress = null;
    }
```

- [ ] **Step 5: Create the DTOs and the mapper method**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/ProgressRequest.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import vasconcelos.silvio.crochet_back.pattern.ProgressStatus;

public record ProgressRequest(
        @NotNull ProgressStatus status,
        @NotNull @PositiveOrZero Integer stepIndex,
        @NotNull @PositiveOrZero Integer rowIndex) {
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/ProgressResponse.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import vasconcelos.silvio.crochet_back.pattern.ProgressStatus;

import java.time.Instant;

public record ProgressResponse(
        ProgressStatus status,
        int stepIndex,
        int rowIndex,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt) {
}
```

In `PatternMapper.java`, add the imports `vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse`, then add:

```java
    /** {@code null} when the pattern is not started. */
    public ProgressResponse toProgressResponse(PatternProgress progress) {
        if (progress == null) {
            return null;
        }
        return new ProgressResponse(
                progress.getStatus(),
                progress.getStepPosition(),
                progress.getRowPosition(),
                progress.getStartedAt(),
                progress.getUpdatedAt(),
                progress.getFinishedAt());
    }
```

- [ ] **Step 6: Create the exceptions and map them**

`src/main/java/vasconcelos/silvio/crochet_back/config/InvalidFieldException.java`:

```java
package vasconcelos.silvio.crochet_back.config;

/** A request field that is well-formed but rejected by a business rule; rendered as a 400 field error. */
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressNotFoundException.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

public class ProgressNotFoundException extends RuntimeException {

    public ProgressNotFoundException(long patternId) {
        super("Pattern " + patternId + " is not started");
    }
}
```

In `ApiExceptionHandler.java`, add the import `vasconcelos.silvio.crochet_back.pattern.ProgressNotFoundException`, then add:

```java
    @ExceptionHandler(ProgressNotFoundException.class)
    ProblemDetail handleProgressNotFound(ProgressNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidFieldException.class)
    ProblemDetail handleInvalidField(InvalidFieldException ex) {
        return validationProblem(List.of(Map.of("field", ex.getField(), "message", ex.getMessage())));
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }
```

Also make `handleMethodArgumentNotValid` reuse the helper. Replace the whole method with:

```java
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
    }
```

- [ ] **Step 7: Create `ProgressService` and `ProgressController`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressService.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.config.InvalidFieldException;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ProgressService {

    private final PatternService patterns;
    private final PatternRepository repository;
    private final PatternMapper mapper;

    public ProgressResponse update(long ownerId, long patternId, ProgressRequest request) {
        Pattern pattern = patterns.find(ownerId, patternId);
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
        PatternProgress progress = pattern.updateProgress(request.status(), stepIndex, rowIndex);
        repository.flush();
        return mapper.toProgressResponse(progress);
    }

    public void abandon(long ownerId, long patternId) {
        Pattern pattern = patterns.find(ownerId, patternId);
        if (pattern.getProgress() == null) {
            throw new ProgressNotFoundException(patternId);
        }
        pattern.clearProgress();
    }
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/ProgressController.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.AccountPrincipal;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse;

@RestController
@RequestMapping("/api/patterns/{id}/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService service;

    @PutMapping
    public ProgressResponse update(
            @AuthenticationPrincipal AccountPrincipal principal,
            @PathVariable long id,
            @Valid @RequestBody ProgressRequest request) {
        return service.update(principal.id(), id, request);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abandon(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable long id) {
        service.abandon(principal.id(), id);
    }
}
```

`ProgressService` runs in its own `@Transactional`; the read-only `PatternService.find` joins that transaction, so the entity it returns stays managed and the progress change is flushed.

- [ ] **Step 8: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

If `abandonDeletesProgressThenReportsNotStarted` fails because the row is still present, orphan removal did not fire on the inverse side of the one-to-one. In that case, delete the row explicitly in `ProgressService.abandon`: inject `EntityManager` and call `entityManager.remove(pattern.getProgress())` before `pattern.clearProgress()`. Record a ruling.

- [ ] **Step 9: Commit**

```bash
git add src/main src/test
git commit -m "feat: add pattern progress endpoints"
```

---

### Task 5: Progress in pattern responses and clamping on replace

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternSummaryRow.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternSummary.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternResponse.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternRepository.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternMapper.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternProgress.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternService.java`
- Modify: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternRepositoryTests.java`
- Modify: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternMapperTest.java`
- Modify: `src/test/java/vasconcelos/silvio/crochet_back/pattern/ProgressApiTests.java`

**Interfaces:**
- Consumes: `PatternProgress`, `Pattern.updateProgress`, `PatternMapper.toProgressResponse` (Task 4); `PatternRepository.findAllSummaries(Long)` (Task 3).
- Produces:
  - `record PatternSummaryRow(Long id, String title, String description, BigDecimal hookSizeMm, String yarn, String imageUrl, Integer stepCount, ProgressStatus status, Integer stepPosition, Integer rowPosition, Instant startedAt, Instant updatedAt, Instant finishedAt)`.
  - `PatternRepository.findAllSummaries(Long): List<PatternSummaryRow>`.
  - `PatternSummary` and `PatternResponse` each gain a last component `ProgressResponse progress`.
  - `PatternMapper.toSummary(PatternSummaryRow): PatternSummary`.
  - `PatternProgress.clampTo(List<Step>)`.

- [ ] **Step 1: Write the failing tests**

Add to `ProgressApiTests` the import `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get`, then these members:

```java
    static final String ONE_STEP = PatternJson.withSteps("\"title\": \"Chat v2\",", """
            [ { "title": "Unique", "rows": [
                { "number": "1", "value": "8ms (8)" },
                { "number": "2", "value": "8aug (16)" } ] } ]""");

    @Test
    void progressIsNullUntilStartedThenAppearsInDetailAndSummary() throws Exception {
        mvc.perform(get(location)).andExpect(jsonPath("$.progress").doesNotExist());
        mvc.perform(get("/api/patterns")).andExpect(jsonPath("$[0].progress").doesNotExist());

        putProgressOk(progress("IN_PROGRESS", 1, 0));

        mvc.perform(get(location))
                .andExpect(jsonPath("$.progress.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.progress.stepIndex").value(1))
                .andExpect(jsonPath("$.progress.rowIndex").value(0))
                .andExpect(jsonPath("$.progress.startedAt").value(notNullValue()));
        mvc.perform(get("/api/patterns"))
                .andExpect(jsonPath("$[0].progress.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[0].progress.stepIndex").value(1))
                .andExpect(jsonPath("$[0].progress.rowIndex").value(0));
    }

    @Test
    void replacingPatternClampsRowIntoNewStructure() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 2)); // third row of "Tête"

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(ONE_STEP)).andExpect(status().isOk());

        mvc.perform(get(location))
                .andExpect(jsonPath("$.progress.stepIndex").value(0))
                .andExpect(jsonPath("$.progress.rowIndex").value(1))
                .andExpect(jsonPath("$.progress.status").value("IN_PROGRESS"));
    }

    @Test
    void replacingPatternClampsStepAndKeepsStatus() throws Exception {
        String finished = putProgressOk(progress("FINISHED", 1, 0)); // "Corps"

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(ONE_STEP)).andExpect(status().isOk());

        String detail = mvc.perform(get(location)).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(detail, "$.progress.stepIndex")).isEqualTo(0);
        assertThat((Integer) JsonPath.read(detail, "$.progress.rowIndex")).isEqualTo(0);
        assertThat((String) JsonPath.read(detail, "$.progress.status")).isEqualTo("FINISHED");
        assertThat(instant(detail, "$.progress.startedAt")).isEqualTo(instant(finished, "$.startedAt"));
    }

    @Test
    void replacingPatternKeepsProgressStillInRange() throws Exception {
        putProgressOk(progress("IN_PROGRESS", 0, 1));

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(CHAT)).andExpect(status().isOk());

        mvc.perform(get(location))
                .andExpect(jsonPath("$.progress.stepIndex").value(0))
                .andExpect(jsonPath("$.progress.rowIndex").value(1));
    }
```

In `PatternMapperTest`, add:

```java
    @Test
    void toResponseHasNoProgressUntilStartedThenMapsIt() {
        Pattern pattern = mapper.toEntity(request);
        assertThat(mapper.toResponse(pattern).progress()).isNull();

        pattern.updateProgress(ProgressStatus.IN_PROGRESS, 1, 0);

        ProgressResponse progress = mapper.toResponse(pattern).progress();
        assertThat(progress.status()).isEqualTo(ProgressStatus.IN_PROGRESS);
        assertThat(progress.stepIndex()).isEqualTo(1);
        assertThat(progress.rowIndex()).isZero();
        assertThat(progress.startedAt()).isNotNull();
        assertThat(progress.finishedAt()).isNull();
    }
```

with the import `vasconcelos.silvio.crochet_back.pattern.dto.ProgressResponse`.

In `PatternRepositoryTests`, replace every `PatternSummary` with `PatternSummaryRow` (the import `vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary` is removed; `PatternSummaryRow` is in the same package). The accessors used (`title`, `stepCount`, `id`) keep their names.

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest='ProgressApiTests,PatternMapperTest,PatternRepositoryTests'`
Expected: FAIL — compilation errors (`progress()` not found on `PatternResponse`, `PatternSummaryRow` not found).

- [ ] **Step 3: Add `progress` to the DTOs and create the summary row**

`PatternSummary.java`: add a last component `ProgressResponse progress` (after `Integer stepCount`).

`PatternResponse.java`: add a last component `ProgressResponse progress` (after `List<StepResponse> steps`).

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternSummaryRow.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import java.math.BigDecimal;
import java.time.Instant;

/** Flat result of the summary query; progress columns are null when the pattern is not started. */
public record PatternSummaryRow(
        Long id,
        String title,
        String description,
        BigDecimal hookSizeMm,
        String yarn,
        String imageUrl,
        Integer stepCount,
        ProgressStatus status,
        Integer stepPosition,
        Integer rowPosition,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt) {
}
```

- [ ] **Step 4: Update the repository query**

In `PatternRepository`, replace `findAllSummaries` with:

```java
    @Query("""
            select new vasconcelos.silvio.crochet_back.pattern.PatternSummaryRow(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps),
                pr.status, pr.stepPosition, pr.rowPosition, pr.startedAt, pr.updatedAt, pr.finishedAt)
            from Pattern p left join p.progress pr
            where p.ownerId = :ownerId
            order by lower(p.title), p.id
            """)
    List<PatternSummaryRow> findAllSummaries(@Param("ownerId") Long ownerId);
```

Remove the now-unused import of `PatternSummary` from the repository.

- [ ] **Step 5: Update the mapper**

In `PatternMapper.toResponse`, pass `toProgressResponse(pattern.getProgress())` as the new last constructor argument of `PatternResponse`. Then add:

```java
    public PatternSummary toSummary(PatternSummaryRow row) {
        ProgressResponse progress = row.status() == null ? null : new ProgressResponse(
                row.status(), row.stepPosition(), row.rowPosition(),
                row.startedAt(), row.updatedAt(), row.finishedAt());
        return new PatternSummary(
                row.id(), row.title(), row.description(), row.hookSizeMm(), row.yarn(), row.imageUrl(),
                row.stepCount(), progress);
    }
```

with the import `vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary`.

- [ ] **Step 6: Clamp on replace**

In `PatternProgress.java`, add the import `java.util.List`, then add:

```java
    /**
     * Moves the position back inside {@code steps} after the pattern was replaced; status, startedAt
     * and updatedAt are left unchanged because the user did not move.
     */
    void clampTo(List<Step> steps) {
        stepPosition = Math.min(stepPosition, steps.size() - 1);
        rowPosition = Math.min(rowPosition, steps.get(stepPosition).getRows().size() - 1);
    }
```

In `PatternService`:
- `list` becomes:

```java
    public List<PatternSummary> list(long ownerId) {
        return repository.findAllSummaries(ownerId).stream().map(mapper::toSummary).toList();
    }
```

- In `replace`, insert this immediately after `mapper.toSteps(request.steps()).forEach(pattern::addStep);`:

```java
        if (pattern.getProgress() != null) {
            pattern.getProgress().clampTo(pattern.getSteps());
        }
```

A pattern always has at least one step and every step at least one row (validation in `PatternRequest`), so `clampTo` never sees an empty list.

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test
git commit -m "feat: expose progress in pattern responses and clamp it on replace"
```

---

### Task 6: Change password and delete account

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/dto/ChangePasswordRequest.java`, `DeleteAccountRequest.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/account/AccountSessions.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/account/AccountService.java`
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/account/MeController.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/account/AccountManagementApiTests.java`

**Interfaces:**
- Consumes: `AccountService.get`, `@BcryptPassword`, `Browser`, `TestDatabase` (Task 2); `InvalidFieldException` (Task 4); `PatternJson.CHAT` (existing, package-private in `pattern`, so the test inlines its own pattern JSON).
- Produces:
  - `AccountService.changePassword(long id, String currentPassword, String newPassword)` and `AccountService.delete(long id, String password)`, both throwing `InvalidFieldException`.
  - `AccountSessions.invalidateAllExcept(String principalName, String keptSessionId)`.
  - `PUT /api/me/password` and `DELETE /api/me`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/vasconcelos/silvio/crochet_back/account/AccountManagementApiTests.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
import vasconcelos.silvio.crochet_back.support.Browser;
import vasconcelos.silvio.crochet_back.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountManagementApiTests {

    static final String EMAIL = "moi@exemple.fr";
    static final String PASSWORD = "motdepasse-secret";
    static final String NEW_PASSWORD = "nouveau-secret";
    static final String PATTERN = """
            { "title": "Chat", "steps": [ { "title": "Tête", "rows": [ { "number": "1", "value": "6ms" } ] } ] }""";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        TestDatabase.reset(jdbc);
    }

    static String credentials(String password) {
        return """
                { "email": "%s", "password": "%s" }""".formatted(EMAIL, password);
    }

    static String passwordChange(String current, String next) {
        return """
                { "currentPassword": "%s", "newPassword": "%s" }""".formatted(current, next);
    }

    Browser registered() throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isCreated());
        return browser;
    }

    Browser loggedIn(String password) throws Exception {
        Browser browser = new Browser(mvc).fetchCsrf();
        browser.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(password)))
                .andExpect(status().isOk());
        return browser;
    }

    int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void changePasswordSwitchesCredentials() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        new Browser(mvc).fetchCsrf()
                .perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(credentials(PASSWORD)))
                .andExpect(status().isUnauthorized());
        loggedIn(NEW_PASSWORD);
    }

    @Test
    void changePasswordWithWrongCurrentPasswordIsBadRequest() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange("mauvais-mot-de-passe", NEW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("currentPassword")));

        loggedIn(PASSWORD);
    }

    @Test
    void changePasswordValidatesNewPassword() throws Exception {
        Browser browser = registered();

        browser.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange(PASSWORD, "court")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("newPassword")));
    }

    @Test
    void changePasswordEndsOtherSessionsButKeepsCurrentOne() throws Exception {
        Browser current = registered();
        Browser other = loggedIn(PASSWORD);

        current.perform(put("/api/me/password").contentType(APPLICATION_JSON)
                        .content(passwordChange(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent());

        other.perform(get("/api/me")).andExpect(status().isUnauthorized());
        current.perform(get("/api/me")).andExpect(status().isOk());
    }

    @Test
    void deleteAccountWithWrongPasswordIsBadRequest() throws Exception {
        Browser browser = registered();

        browser.perform(delete("/api/me").contentType(APPLICATION_JSON)
                        .content("{ \"password\": \"mauvais-mot-de-passe\" }"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));

        assertThat(count("account")).isEqualTo(1);
    }

    @Test
    void deleteAccountRemovesEverythingAndEndsAllSessions() throws Exception {
        Browser browser = registered();
        Browser other = loggedIn(PASSWORD);
        String location = browser.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(PATTERN))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        browser.perform(put(location + "/progress").contentType(APPLICATION_JSON)
                        .content("{ \"status\": \"IN_PROGRESS\", \"stepIndex\": 0, \"rowIndex\": 0 }"))
                .andExpect(status().isOk());

        browser.perform(delete("/api/me").contentType(APPLICATION_JSON)
                        .content("{ \"password\": \"%s\" }".formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        browser.perform(get("/api/me")).andExpect(status().isUnauthorized());
        other.perform(get("/api/me")).andExpect(status().isUnauthorized());
        assertThat(count("account")).isZero();
        assertThat(count("pattern")).isZero();
        assertThat(count("step")).isZero();
        assertThat(count("pattern_row")).isZero();
        assertThat(count("pattern_progress")).isZero();
        assertThat(count("spring_session")).isZero();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=AccountManagementApiTests`
Expected: FAIL. `PUT /api/me/password` and `DELETE /api/me` return 405 or 404 instead of 204/400.

- [ ] **Step 3: Create the request records**

`src/main/java/vasconcelos/silvio/crochet_back/account/dto/ChangePasswordRequest.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChangePasswordRequest(@NotBlank String currentPassword, @NotNull @BcryptPassword String newPassword) {
}
```

`src/main/java/vasconcelos/silvio/crochet_back/account/dto/DeleteAccountRequest.java`:

```java
package vasconcelos.silvio.crochet_back.account.dto;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequest(@NotBlank String password) {
}
```

- [ ] **Step 4: Add the service operations**

In `AccountService.java`, add the import `vasconcelos.silvio.crochet_back.config.InvalidFieldException`, then add:

```java
    @Transactional
    public void changePassword(long id, String currentPassword, String newPassword) {
        Account account = get(id);
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new InvalidFieldException("currentPassword", "is incorrect");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword));
    }

    /** Patterns, steps, rows and progress go with the account through ON DELETE CASCADE. */
    @Transactional
    public void delete(long id, String password) {
        Account account = get(id);
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            throw new InvalidFieldException("password", "is incorrect");
        }
        accounts.delete(account);
    }
```

- [ ] **Step 5: Create `AccountSessions`**

`src/main/java/vasconcelos/silvio/crochet_back/account/AccountSessions.java`:

```java
package vasconcelos.silvio.crochet_back.account;

import lombok.RequiredArgsConstructor;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountSessions {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /** Deletes every stored session of the account except {@code keptSessionId}. */
    public void invalidateAllExcept(String principalName, String keptSessionId) {
        sessions.findByPrincipalName(principalName).keySet().stream()
                .filter(sessionId -> !sessionId.equals(keptSessionId))
                .forEach(sessions::deleteById);
    }
}
```

- [ ] **Step 6: Add the endpoints to `MeController`**

Replace `MeController.java` with:

```java
package vasconcelos.silvio.crochet_back.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vasconcelos.silvio.crochet_back.account.dto.AccountResponse;
import vasconcelos.silvio.crochet_back.account.dto.ChangePasswordRequest;
import vasconcelos.silvio.crochet_back.account.dto.DeleteAccountRequest;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountService accountService;
    private final AccountSessions accountSessions;

    @GetMapping
    public AccountResponse me(@AuthenticationPrincipal AccountPrincipal principal) {
        return AccountResponse.from(accountService.get(principal.id()));
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        accountService.changePassword(principal.id(), body.currentPassword(), body.newPassword());
        accountSessions.invalidateAllExcept(principal.getUsername(), request.getSession().getId());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal AccountPrincipal principal,
            @Valid @RequestBody DeleteAccountRequest body,
            HttpServletRequest request) {
        accountService.delete(principal.id(), body.password());
        HttpSession current = request.getSession();
        accountSessions.invalidateAllExcept(principal.getUsername(), current.getId());
        // The current session is invalidated through the request so that Spring Session does not
        // save it again at the end of the request, and expires its cookie.
        current.invalidate();
        SecurityContextHolder.clearContext();
    }
}
```

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: PASS — every test class.

- [ ] **Step 8: Commit**

```bash
git add src/main src/test
git commit -m "feat: add password change and account deletion"
```

- [ ] **Step 9: Manual smoke check**

The host already runs a native PostgreSQL on 5432, so map the compose database to 55432 through an override file outside the repo (scratch directory):

```yaml
services:
  postgres:
    ports: !override
      - "55432:5432"
```

```bash
docker compose -p crochet-smoke -f docker-compose.yml -f <override> up -d
DB_URL=jdbc:postgresql://localhost:55432/crochet ./mvnw spring-boot:run
```

In another terminal, with a cookie jar:

```bash
curl -s -c jar -b jar -o /dev/null -w '%{http_code}\n' localhost:8080/api/auth/csrf
TOKEN=$(grep XSRF-TOKEN jar | awk '{print $7}')
curl -s -c jar -b jar -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"moi@exemple.fr","password":"motdepasse-secret"}' localhost:8080/api/auth/register
curl -s -c jar -b jar localhost:8080/api/me
```

Expected: `204`, then the account JSON twice. Then stop the app and run `docker compose -p crochet-smoke down -v`.
