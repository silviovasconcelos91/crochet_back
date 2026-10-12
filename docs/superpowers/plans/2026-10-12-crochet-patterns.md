# Crochet Patterns Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** REST API (Spring Boot + PostgreSQL) to create, list, read, replace and delete crochet patterns made of ordered steps containing ordered free-text rows.

**Architecture:** Feature package `pattern` (JPA entities `Pattern` → `Step` → `PatternRow`, Spring Data repository, service, controller, manual mapper, record DTOs with Bean Validation). Cross-cutting `config` package (CORS, `ProblemDetail` error handler). Schema owned by Flyway; Hibernate only validates it.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (webmvc, data-jpa, validation, flyway), Hibernate 7.4, PostgreSQL 17, Flyway 12, Lombok, JUnit 5, MockMvc, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-10-12-crochet-patterns-design.md`

## Global Constraints

- Root package `vasconcelos.silvio.crochet_back`; feature code in `...crochet_back.pattern`, DTOs in `...crochet_back.pattern.dto`, cross-cutting in `...crochet_back.config`.
- API prefix `/api`; JSON properties camelCase.
- Schema managed exclusively by Flyway (`src/main/resources/db/migration`); `spring.jpa.hibernate.ddl-auto: validate`; `spring.jpa.open-in-view: false`.
- Table names: `pattern`, `step`, `pattern_row`. Positions are 0-based.
- Errors use `ProblemDetail` (RFC 9457), `Content-Type: application/problem+json`. Validation errors carry property `errors` = list of `{ "field", "message" }`.
- Limits: `title` ≤ 200, `yarn` ≤ 200, `sourceUrl`/`imageUrl` ≤ 500, step `title` ≤ 200, row `number` ≤ 20; `hookSizeMm` > 0, `@Digits(integer = 2, fraction = 2)`.
- No pagination, no auth.
- Datasource via env vars `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, defaulting to the local compose database (`jdbc:postgresql://localhost:5432/crochet`, `crochet`/`crochet`).
- CORS origins from `crochet.cors.allowed-origins`, default `http://localhost:5173`, applied to `/api/**`.
- Tests need Docker running (Testcontainers, image `postgres:17`).
- Run Maven from the repo root with `./mvnw` (Git Bash) or `mvnw.cmd` (PowerShell).

## Review Focus

1. A PUT that changes only rows (no top-level field) must still bump `updatedAt` and keep `createdAt` → pinned in Task 5 (`putChangingOnlyRowsBumpsUpdatedAt`).
2. A `null` element inside `steps` or `rows` (`"steps": [null]`) must give 400, not a 500 NPE in the mapper → pinned in Task 4 (`nullStepOrRowElementIsBadRequest`).
3. A non-numeric id (`/api/patterns/abc`) must give 400, not 500 → pinned in Task 4 (`nonNumericIdIsBadRequest`).
4. Accented text (`Tête`, `maille serrée`) and long row values (> 255 chars, column is `text`) must round-trip unchanged → pinned in Task 4 (`accentsAndLongRowValuesRoundTrip`).
5. A PUT with an invalid body on an existing pattern must leave the stored pattern untouched → pinned in Task 5 (`invalidPutLeavesPatternUnchanged`).

---

## File Structure

```
pom.xml                                                         (modify: deps)
docker-compose.yml                                              (create)
src/main/resources/application.yaml                             (modify)
src/main/resources/db/migration/V1__create_pattern_tables.sql   (create)
src/main/java/vasconcelos/silvio/crochet_back/
  pattern/Pattern.java                  entity, root aggregate
  pattern/Step.java                     entity
  pattern/PatternRow.java               entity
  pattern/PatternRepository.java        Spring Data + summary projection query
  pattern/PatternMapper.java            entity <-> DTO, assigns positions
  pattern/PatternService.java           transactions, CRUD logic
  pattern/PatternController.java        HTTP only
  pattern/PatternNotFoundException.java
  pattern/dto/PatternRequest.java, StepRequest.java, RowRequest.java
  pattern/dto/PatternResponse.java, StepResponse.java, RowResponse.java, PatternSummary.java
  config/ApiExceptionHandler.java       ProblemDetail mapping
  config/CorsProperties.java            @ConfigurationProperties("crochet.cors")
  config/CorsConfig.java                WebMvcConfigurer
src/test/java/vasconcelos/silvio/crochet_back/
  TestcontainersConfiguration.java      shared Postgres container
  CrochetBackApplicationTests.java      (modify: import container config)
  SchemaMigrationTests.java
  pattern/PatternRepositoryTests.java
  pattern/PatternMapperTest.java
  pattern/PatternJson.java              JSON fixtures for API tests
  pattern/PatternApiTests.java          list / get / create / errors
  pattern/PatternWriteApiTests.java     put / delete
  config/CorsTests.java
```

---

### Task 1: Database infrastructure (dependencies, compose, Flyway schema, Testcontainers)

**Files:**
- Modify: `pom.xml` (`<dependencies>` block)
- Create: `docker-compose.yml`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/resources/db/migration/V1__create_pattern_tables.sql`
- Create: `src/test/java/vasconcelos/silvio/crochet_back/TestcontainersConfiguration.java`
- Modify: `src/test/java/vasconcelos/silvio/crochet_back/CrochetBackApplicationTests.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/SchemaMigrationTests.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `TestcontainersConfiguration` (test `@TestConfiguration`, import it with `@Import(TestcontainersConfiguration.class)` on every `@SpringBootTest`); tables `pattern`, `step`, `pattern_row` as in the spec.

- [ ] **Step 1: Add dependencies to `pom.xml`**

Inside `<dependencies>`, after the `spring-boot-starter-webmvc` dependency, add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

After the `spring-boot-starter-webmvc-test` dependency, add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
```

All versions come from the Spring Boot parent BOM (Flyway 12.4.0, PostgreSQL driver 42.7.13, Testcontainers 2.0.5). Do not add `<version>` tags.

- [ ] **Step 2: Create `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: crochet
      POSTGRES_USER: crochet
      POSTGRES_PASSWORD: crochet
    ports:
      - "5432:5432"
    volumes:
      - crochet-data:/var/lib/postgresql/data

volumes:
  crochet-data:
```

- [ ] **Step 3: Replace `src/main/resources/application.yaml`**

```yaml
spring:
  application:
    name: crochet_back
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/crochet}
    username: ${DB_USERNAME:crochet}
    password: ${DB_PASSWORD:crochet}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_batch_fetch_size: 50
```

- [ ] **Step 4: Create the shared Testcontainers configuration**

`src/test/java/vasconcelos/silvio/crochet_back/TestcontainersConfiguration.java`:

```java
package vasconcelos.silvio.crochet_back;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:17");
    }
}
```

`@ServiceConnection` overrides the `spring.datasource.*` values from `application.yaml` with the container's.

- [ ] **Step 5: Make the existing context test use the container**

Replace `src/test/java/vasconcelos/silvio/crochet_back/CrochetBackApplicationTests.java`:

```java
package vasconcelos.silvio.crochet_back;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CrochetBackApplicationTests {

    @Test
    void contextLoads() {
    }

}
```

- [ ] **Step 6: Write the failing schema test**

`src/test/java/vasconcelos/silvio/crochet_back/SchemaMigrationTests.java`:

```java
package vasconcelos.silvio.crochet_back;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayCreatesPatternTables() {
        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_name in ('pattern', 'step', 'pattern_row')
                order by table_name
                """, String.class);

        assertThat(tables).containsExactly("pattern", "pattern_row", "step");
    }
}
```

- [ ] **Step 7: Run it to verify it fails**

Run: `./mvnw test -Dtest=SchemaMigrationTests`
Expected: FAIL — `Expecting actual: [] to contain exactly ...` (no migration yet).

- [ ] **Step 8: Write the migration**

`src/main/resources/db/migration/V1__create_pattern_tables.sql`:

```sql
CREATE TABLE pattern
(
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    hook_size_mm NUMERIC(4, 2),
    yarn         VARCHAR(200),
    source_url   VARCHAR(500),
    image_url    VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE step
(
    id         BIGSERIAL PRIMARY KEY,
    pattern_id BIGINT       NOT NULL REFERENCES pattern (id) ON DELETE CASCADE,
    position   INT          NOT NULL,
    title      VARCHAR(200) NOT NULL,
    CONSTRAINT uk_step_pattern_position UNIQUE (pattern_id, position)
);

CREATE TABLE pattern_row
(
    id       BIGSERIAL PRIMARY KEY,
    step_id  BIGINT      NOT NULL REFERENCES step (id) ON DELETE CASCADE,
    position INT         NOT NULL,
    number   VARCHAR(20) NOT NULL,
    value    TEXT        NOT NULL,
    CONSTRAINT uk_pattern_row_step_position UNIQUE (step_id, position)
);
```

The unique constraints start with the FK column, so they also serve as FK indexes.

- [ ] **Step 9: Run all tests to verify they pass**

Run: `./mvnw test`
Expected: PASS — `SchemaMigrationTests` and `CrochetBackApplicationTests` green.

- [ ] **Step 10: Commit**

```bash
git add pom.xml docker-compose.yml src/main/resources src/test/java/vasconcelos/silvio/crochet_back
git commit -m "feat: add PostgreSQL, Flyway schema and Testcontainers setup"
```

---

### Task 2: JPA entities and repository

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/Pattern.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/Step.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternRow.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternSummary.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternRepository.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternRepositoryTests.java`

**Interfaces:**
- Consumes: `TestcontainersConfiguration` (Task 1), tables from Task 1.
- Produces:
  - `Pattern` — `Long getId()`, `String getTitle()/setTitle(String)`, `getDescription()/setDescription(String)`, `BigDecimal getHookSizeMm()/setHookSizeMm(BigDecimal)`, `getYarn()/setYarn(String)`, `getSourceUrl()/setSourceUrl(String)`, `getImageUrl()/setImageUrl(String)`, `Instant getCreatedAt()`, `Instant getUpdatedAt()`, `List<Step> getSteps()` (no setter), `void addStep(Step)`, `void touch()` (sets `updatedAt` to now).
  - `Step(int position, String title)` — `getPosition()`, `getTitle()`, `getPattern()`, `List<PatternRow> getRows()`, `void addRow(PatternRow)`.
  - `PatternRow(int position, String number, String value)` — `getPosition()`, `getNumber()`, `getValue()`, `getStep()`.
  - `record PatternSummary(Long id, String title, String description, BigDecimal hookSizeMm, String yarn, String imageUrl, Integer stepCount)`.
  - `PatternRepository extends JpaRepository<Pattern, Long>` with `List<PatternSummary> findAllSummaries()` (sorted by `lower(title)`).

- [ ] **Step 1: Write the failing repository test**

`src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternRepositoryTests.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;
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
    EntityManager em;

    @Test
    void persistsStepsAndRowsOrderedByPosition() {
        Pattern pattern = new Pattern();
        pattern.setTitle("Chat");
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

        List<PatternSummary> summaries = repository.findAllSummaries();

        assertThat(summaries).extracting(PatternSummary::title).containsExactly("abeille", "Chat", "zèbre");
        assertThat(summaries).extracting(PatternSummary::stepCount).containsExactly(3, 2, 1);
    }

    private static Pattern pattern(String title, int stepCount) {
        Pattern pattern = new Pattern();
        pattern.setTitle(title);
        for (int i = 0; i < stepCount; i++) {
            Step step = new Step(i, "Étape " + i);
            step.addRow(new PatternRow(0, "1", "6ms"));
            pattern.addStep(step);
        }
        return pattern;
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=PatternRepositoryTests`
Expected: FAIL — compilation errors (`Pattern`, `Step`, `PatternRow`, `PatternRepository`, `PatternSummary` not found).

- [ ] **Step 3: Create `Pattern`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/Pattern.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pattern")
@Getter
@Setter
@NoArgsConstructor
public class Pattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    private String description;

    @Column(name = "hook_size_mm", precision = 4, scale = 2)
    private BigDecimal hookSizeMm;

    @Column(length = 200)
    private String yarn;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @OneToMany(mappedBy = "pattern", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @Setter(AccessLevel.NONE)
    private List<Step> steps = new ArrayList<>();

    public void addStep(Step step) {
        step.setPattern(this);
        steps.add(step);
    }

    /** Marks the pattern as modified; call on every replace, even when only steps/rows changed. */
    public void touch() {
        updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }
}
```

- [ ] **Step 4: Create `Step`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/Step.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "step")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Step {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pattern_id", nullable = false)
    @Setter(AccessLevel.PACKAGE)
    private Pattern pattern;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 200)
    private String title;

    @OneToMany(mappedBy = "step", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<PatternRow> rows = new ArrayList<>();

    public Step(int position, String title) {
        this.position = position;
        this.title = title;
    }

    public void addRow(PatternRow row) {
        row.setStep(this);
        rows.add(row);
    }
}
```

- [ ] **Step 5: Create `PatternRow`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternRow.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pattern_row")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PatternRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_id", nullable = false)
    @Setter(AccessLevel.PACKAGE)
    private Step step;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 20)
    private String number;

    @Column(nullable = false)
    private String value;

    public PatternRow(int position, String number, String value) {
        this.position = position;
        this.number = number;
        this.value = value;
    }
}
```

- [ ] **Step 6: Create `PatternSummary`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternSummary.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import java.math.BigDecimal;

public record PatternSummary(
        Long id,
        String title,
        String description,
        BigDecimal hookSizeMm,
        String yarn,
        String imageUrl,
        Integer stepCount) {
}
```

`stepCount` is `Integer` (not `int`) because JPQL `size()` returns `Integer` and the constructor expression must match.

- [ ] **Step 7: Create `PatternRepository`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternRepository.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary;

import java.util.List;

public interface PatternRepository extends JpaRepository<Pattern, Long> {

    @Query("""
            select new vasconcelos.silvio.crochet_back.pattern.dto.PatternSummary(
                p.id, p.title, p.description, p.hookSizeMm, p.yarn, p.imageUrl, size(p.steps))
            from Pattern p
            order by lower(p.title)
            """)
    List<PatternSummary> findAllSummaries();
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test`
Expected: PASS — all tests, including `ddl-auto: validate` accepting the entity mappings.

If startup fails with `Schema-validation: wrong column type encountered in column [...]`, fix the entity mapping, never the migration. For a `text` column, add `@Column(columnDefinition = "text")`. For `created_at`/`updated_at`, add `@Column(columnDefinition = "timestamptz")`. Then re-run.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/pattern src/test/java/vasconcelos/silvio/crochet_back/pattern
git commit -m "feat: add pattern JPA entities and repository"
```

---

### Task 3: DTOs and mapper

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternRequest.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/StepRequest.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/RowRequest.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternResponse.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/StepResponse.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/RowResponse.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternMapper.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternMapperTest.java`

**Interfaces:**
- Consumes: `Pattern`, `Step`, `PatternRow` (Task 2).
- Produces:
  - `record PatternRequest(String title, String description, BigDecimal hookSizeMm, String yarn, String sourceUrl, String imageUrl, List<StepRequest> steps)` with Bean Validation.
  - `record StepRequest(String title, List<RowRequest> rows)`, `record RowRequest(String number, String value)`.
  - `record PatternResponse(Long id, String title, String description, BigDecimal hookSizeMm, String yarn, String sourceUrl, String imageUrl, Instant createdAt, Instant updatedAt, List<StepResponse> steps)`.
  - `record StepResponse(String title, List<RowResponse> rows)`, `record RowResponse(String number, String value)`.
  - `@Component PatternMapper`: `Pattern toEntity(PatternRequest)`, `void applyFields(PatternRequest, Pattern)`, `List<Step> toSteps(List<StepRequest>)`, `PatternResponse toResponse(Pattern)`.

- [ ] **Step 1: Create the request records**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternRequest.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record PatternRequest(
        @NotBlank @Size(max = 200) String title,
        String description,
        @Positive @Digits(integer = 2, fraction = 2) BigDecimal hookSizeMm,
        @Size(max = 200) String yarn,
        @Size(max = 500) String sourceUrl,
        @Size(max = 500) String imageUrl,
        @NotEmpty List<@NotNull @Valid StepRequest> steps) {
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/StepRequest.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StepRequest(
        @NotBlank @Size(max = 200) String title,
        @NotEmpty List<@NotNull @Valid RowRequest> rows) {
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/RowRequest.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RowRequest(
        @NotBlank @Size(max = 20) String number,
        @NotBlank String value) {
}
```

- [ ] **Step 2: Create the response records**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/PatternResponse.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PatternResponse(
        Long id,
        String title,
        String description,
        BigDecimal hookSizeMm,
        String yarn,
        String sourceUrl,
        String imageUrl,
        Instant createdAt,
        Instant updatedAt,
        List<StepResponse> steps) {
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/StepResponse.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

import java.util.List;

public record StepResponse(String title, List<RowResponse> rows) {
}
```

`src/main/java/vasconcelos/silvio/crochet_back/pattern/dto/RowResponse.java`:

```java
package vasconcelos.silvio.crochet_back.pattern.dto;

public record RowResponse(String number, String value) {
}
```

- [ ] **Step 3: Write the failing mapper test**

`src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternMapperTest.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.Test;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.RowRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.RowResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.StepRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.StepResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatternMapperTest {

    private final PatternMapper mapper = new PatternMapper();

    private final PatternRequest request = new PatternRequest(
            "Chat", "Petit chat", new BigDecimal("3.5"), "Coton", "https://src", "https://img",
            List.of(
                    new StepRequest("Tête", List.of(
                            new RowRequest("1", "6ms (6)"),
                            new RowRequest("2", "6aug (12)"))),
                    new StepRequest("Corps", List.of(
                            new RowRequest("1-5", "12ms (12)")))));

    @Test
    void toEntityCopiesFieldsAndAssignsPositionsFromListOrder() {
        Pattern pattern = mapper.toEntity(request);

        assertThat(pattern.getTitle()).isEqualTo("Chat");
        assertThat(pattern.getDescription()).isEqualTo("Petit chat");
        assertThat(pattern.getHookSizeMm()).isEqualByComparingTo("3.5");
        assertThat(pattern.getYarn()).isEqualTo("Coton");
        assertThat(pattern.getSourceUrl()).isEqualTo("https://src");
        assertThat(pattern.getImageUrl()).isEqualTo("https://img");

        assertThat(pattern.getSteps()).extracting(Step::getPosition).containsExactly(0, 1);
        assertThat(pattern.getSteps()).extracting(Step::getTitle).containsExactly("Tête", "Corps");
        assertThat(pattern.getSteps()).allSatisfy(step -> assertThat(step.getPattern()).isSameAs(pattern));

        Step head = pattern.getSteps().getFirst();
        assertThat(head.getRows()).extracting(PatternRow::getPosition).containsExactly(0, 1);
        assertThat(head.getRows()).extracting(PatternRow::getNumber).containsExactly("1", "2");
        assertThat(head.getRows()).extracting(PatternRow::getValue).containsExactly("6ms (6)", "6aug (12)");
        assertThat(head.getRows()).allSatisfy(row -> assertThat(row.getStep()).isSameAs(head));
    }

    @Test
    void toResponseKeepsStepAndRowOrder() {
        PatternResponse response = mapper.toResponse(mapper.toEntity(request));

        assertThat(response.title()).isEqualTo("Chat");
        assertThat(response.hookSizeMm()).isEqualByComparingTo("3.5");
        assertThat(response.steps()).extracting(StepResponse::title).containsExactly("Tête", "Corps");
        assertThat(response.steps().getFirst().rows()).containsExactly(
                new RowResponse("1", "6ms (6)"),
                new RowResponse("2", "6aug (12)"));
    }

    @Test
    void applyFieldsOverwritesTopLevelFieldsOnly() {
        Pattern pattern = mapper.toEntity(request);
        PatternRequest update = new PatternRequest(
                "Chat v2", null, null, null, null, null, List.of());

        mapper.applyFields(update, pattern);

        assertThat(pattern.getTitle()).isEqualTo("Chat v2");
        assertThat(pattern.getDescription()).isNull();
        assertThat(pattern.getHookSizeMm()).isNull();
        assertThat(pattern.getYarn()).isNull();
        assertThat(pattern.getSourceUrl()).isNull();
        assertThat(pattern.getImageUrl()).isNull();
        assertThat(pattern.getSteps()).hasSize(2);
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw test -Dtest=PatternMapperTest`
Expected: FAIL — compilation error, `PatternMapper` not found.

- [ ] **Step 5: Implement `PatternMapper`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternMapper.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import org.springframework.stereotype.Component;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.PatternResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.RowRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.RowResponse;
import vasconcelos.silvio.crochet_back.pattern.dto.StepRequest;
import vasconcelos.silvio.crochet_back.pattern.dto.StepResponse;

import java.util.ArrayList;
import java.util.List;

@Component
public class PatternMapper {

    public Pattern toEntity(PatternRequest request) {
        Pattern pattern = new Pattern();
        applyFields(request, pattern);
        toSteps(request.steps()).forEach(pattern::addStep);
        return pattern;
    }

    public void applyFields(PatternRequest request, Pattern pattern) {
        pattern.setTitle(request.title());
        pattern.setDescription(request.description());
        pattern.setHookSizeMm(request.hookSizeMm());
        pattern.setYarn(request.yarn());
        pattern.setSourceUrl(request.sourceUrl());
        pattern.setImageUrl(request.imageUrl());
    }

    /** Builds detached steps whose positions follow the list order (0-based). */
    public List<Step> toSteps(List<StepRequest> requests) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            StepRequest stepRequest = requests.get(i);
            Step step = new Step(i, stepRequest.title());
            List<RowRequest> rows = stepRequest.rows();
            for (int j = 0; j < rows.size(); j++) {
                step.addRow(new PatternRow(j, rows.get(j).number(), rows.get(j).value()));
            }
            steps.add(step);
        }
        return steps;
    }

    public PatternResponse toResponse(Pattern pattern) {
        List<StepResponse> steps = pattern.getSteps().stream()
                .map(step -> new StepResponse(step.getTitle(), step.getRows().stream()
                        .map(row -> new RowResponse(row.getNumber(), row.getValue()))
                        .toList()))
                .toList();
        return new PatternResponse(
                pattern.getId(),
                pattern.getTitle(),
                pattern.getDescription(),
                pattern.getHookSizeMm(),
                pattern.getYarn(),
                pattern.getSourceUrl(),
                pattern.getImageUrl(),
                pattern.getCreatedAt(),
                pattern.getUpdatedAt(),
                steps);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=PatternMapperTest`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/pattern src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternMapperTest.java
git commit -m "feat: add pattern DTOs and mapper"
```

---

### Task 4: Read and create API with error handling

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternNotFoundException.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternService.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternController.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/config/ApiExceptionHandler.java`
- Create: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternJson.java`
- Test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternApiTests.java`

**Interfaces:**
- Consumes: `PatternRepository.findAllSummaries()`, `findById`, `save` (Task 2); `PatternMapper` (Task 3); all DTO records.
- Produces:
  - `PatternNotFoundException(long id)` (unchecked, message `"Pattern <id> not found"`).
  - `@Service PatternService`: `List<PatternSummary> list()`, `PatternResponse get(long id)`, `PatternResponse create(PatternRequest)`, private `Pattern find(long id)`.
  - `@RestController PatternController` at `/api/patterns`: `GET`, `GET /{id}`, `POST`.
  - `@RestControllerAdvice ApiExceptionHandler extends ResponseEntityExceptionHandler`.
  - Test helper `PatternJson`: `String CHAT` (2 steps "Tête" 3 rows, "Corps" 1 row), `String VALID_STEPS`, `static String withSteps(String fields, String steps)`, `static String titled(String title)`, `static String create(MockMvc mvc, String json)` returning the `Location` header.

- [ ] **Step 1: Create the JSON fixtures helper**

`src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternJson.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

final class PatternJson {

    static final String CHAT = """
            {
              "title": "Amigurumi Chat",
              "description": "Petit chat",
              "hookSizeMm": 3.5,
              "yarn": "Coton 8/4",
              "sourceUrl": "https://example.com/chat",
              "imageUrl": "https://example.com/chat.jpg",
              "steps": [
                { "title": "Tête", "rows": [
                    { "number": "1", "value": "6ms dans cercle magique (6)" },
                    { "number": "2", "value": "6aug (12)" },
                    { "number": "3", "value": "3ms, [1ms, 4aug]x6 (34)" } ] },
                { "title": "Corps", "rows": [
                    { "number": "1-10", "value": "34ms (34)" } ] }
              ]
            }
            """;

    static final String VALID_STEPS = """
            [ { "title": "A", "rows": [ { "number": "1", "value": "6ms" } ] } ]""";

    private PatternJson() {
    }

    /** {@code fields} is a JSON fragment ending with a comma, e.g. {@code "\"title\": \"T\","}. */
    static String withSteps(String fields, String steps) {
        return "{" + fields + "\"steps\": " + steps + "}";
    }

    static String titled(String title) {
        return withSteps("\"title\": \"" + title + "\",", VALID_STEPS);
    }

    /** POSTs the pattern, asserts 201 and returns the Location header. */
    static String create(MockMvc mvc, String json) throws Exception {
        return mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
    }
}
```

- [ ] **Step 2: Write the failing API tests**

`src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternApiTests.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.VALID_STEPS;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.titled;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.withSteps;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PatternApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE pattern RESTART IDENTITY CASCADE");
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(CHAT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/patterns/\\d+")))
                .andExpect(jsonPath("$.id").value(notNullValue()))
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$.createdAt").value(notNullValue()))
                .andExpect(jsonPath("$.updatedAt").value(notNullValue()))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }

    @Test
    void getReturnsCreatedPatternWithStepAndRowOrder() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$.description").value("Petit chat"))
                .andExpect(jsonPath("$.hookSizeMm").value(3.5))
                .andExpect(jsonPath("$.yarn").value("Coton 8/4"))
                .andExpect(jsonPath("$.sourceUrl").value("https://example.com/chat"))
                .andExpect(jsonPath("$.imageUrl").value("https://example.com/chat.jpg"))
                .andExpect(jsonPath("$.steps[0].title").value("Tête"))
                .andExpect(jsonPath("$.steps[0].rows[0].number").value("1"))
                .andExpect(jsonPath("$.steps[0].rows[0].value").value("6ms dans cercle magique (6)"))
                .andExpect(jsonPath("$.steps[0].rows[1].value").value("6aug (12)"))
                .andExpect(jsonPath("$.steps[0].rows[2].value").value("3ms, [1ms, 4aug]x6 (34)"))
                .andExpect(jsonPath("$.steps[1].title").value("Corps"))
                .andExpect(jsonPath("$.steps[1].rows[0].number").value("1-10"));
    }

    @Test
    void listReturnsSummariesSortedByTitle() throws Exception {
        create(mvc, titled("Zèbre"));
        create(mvc, CHAT);

        mvc.perform(get("/api/patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$[0].stepCount").value(2))
                .andExpect(jsonPath("$[0].imageUrl").value("https://example.com/chat.jpg"))
                .andExpect(jsonPath("$[0].steps").doesNotExist())
                .andExpect(jsonPath("$[1].title").value("Zèbre"))
                .andExpect(jsonPath("$[1].stepCount").value(1));
    }

    @Test
    void listIsEmptyArrayWhenNoPattern() throws Exception {
        mvc.perform(get("/api/patterns"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void unknownIdIsNotFoundProblem() throws Exception {
        mvc.perform(get("/api/patterns/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Pattern 999 not found"));
    }

    @Test
    void nonNumericIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/patterns/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    static Stream<Arguments> invalidRequests() {
        String t = "\"title\": \"T\",";
        String longText201 = "a".repeat(201);
        String longText501 = "a".repeat(501);
        return Stream.of(
                Arguments.of(withSteps("\"title\": \" \",", VALID_STEPS), "title"),
                Arguments.of(withSteps("", VALID_STEPS), "title"),
                Arguments.of(withSteps("\"title\": \"" + longText201 + "\",", VALID_STEPS), "title"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": 0,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": -1,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": 123.4,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"hookSizeMm\": 3.555,", VALID_STEPS), "hookSizeMm"),
                Arguments.of(withSteps(t + "\"yarn\": \"" + longText201 + "\",", VALID_STEPS), "yarn"),
                Arguments.of(withSteps(t + "\"sourceUrl\": \"" + longText501 + "\",", VALID_STEPS), "sourceUrl"),
                Arguments.of(withSteps(t + "\"imageUrl\": \"" + longText501 + "\",", VALID_STEPS), "imageUrl"),
                Arguments.of("{" + t.substring(0, t.length() - 1) + "}", "steps"),
                Arguments.of(withSteps(t, "[]"), "steps"),
                Arguments.of(withSteps(t, """
                        [ { "title": "", "rows": [ { "number": "1", "value": "6ms" } ] } ]"""), "steps[0].title"),
                Arguments.of(withSteps(t, """
                        [ { "title": "%s", "rows": [ { "number": "1", "value": "6ms" } ] } ]"""
                        .formatted(longText201)), "steps[0].title"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [] } ]"""), "steps[0].rows"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A" } ]"""), "steps[0].rows"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": " ", "value": "6ms" } ] } ]"""),
                        "steps[0].rows[0].number"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": "%s", "value": "6ms" } ] } ]"""
                        .formatted("1".repeat(21))), "steps[0].rows[0].number"),
                Arguments.of(withSteps(t, """
                        [ { "title": "A", "rows": [ { "number": "1", "value": "" } ] } ]"""),
                        "steps[0].rows[0].value"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidRequestIsBadRequestNamingTheField(String json, String field) throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)))
                .andExpect(jsonPath("$.errors[0].message").value(notNullValue()));

        mvc.perform(get("/api/patterns")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void nullStepOrRowElementIsBadRequest() throws Exception {
        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON)
                        .content(withSteps("\"title\": \"T\",", "[null]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(endsWith("steps[0]"))));

        mvc.perform(post("/api/patterns").contentType(APPLICATION_JSON)
                        .content(withSteps("\"title\": \"T\",", """
                                [ { "title": "A", "rows": [null] } ]""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem(endsWith("steps[0].rows[0]"))));
    }

    @Test
    void accentsAndLongRowValuesRoundTrip() throws Exception {
        String longValue = "1ms, 1aug, ".repeat(60).trim();
        String json = withSteps("\"title\": \"Tête de chat à crocheter\",", """
                [ { "title": "Étape maille serrée", "rows": [ { "number": "1", "value": "%s" } ] } ]"""
                .formatted(longValue));

        String location = create(mvc, json);

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Tête de chat à crocheter"))
                .andExpect(jsonPath("$.steps[0].title").value("Étape maille serrée"))
                .andExpect(jsonPath("$.steps[0].rows[0].value").value(longValue));
    }
}
```

Notes for the implementer:
- `hasItem(endsWith("steps[0]"))` is deliberate. The exact path Spring reports for a null container element can vary between `steps[0]` and `steps[0].<list element>`. `endsWith` accepts the first; if Spring reports the second, change the matcher to `startsWith("steps[0]")` and say so in the commit message.
- `longValue` is about 660 characters, more than the 255 of a default `VARCHAR`.

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -Dtest=PatternApiTests`
Expected: FAIL — every test fails with 404 (no controller mapped). The parameterized tests also fail on status.

- [ ] **Step 4: Create `PatternNotFoundException`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternNotFoundException.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

public class PatternNotFoundException extends RuntimeException {

    public PatternNotFoundException(long id) {
        super("Pattern " + id + " not found");
    }
}
```

- [ ] **Step 5: Create `PatternService`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternService.java`:

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

    public List<PatternSummary> list() {
        return repository.findAllSummaries();
    }

    public PatternResponse get(long id) {
        return mapper.toResponse(find(id));
    }

    @Transactional
    public PatternResponse create(PatternRequest request) {
        return mapper.toResponse(repository.save(mapper.toEntity(request)));
    }

    private Pattern find(long id) {
        return repository.findById(id).orElseThrow(() -> new PatternNotFoundException(id));
    }
}
```

- [ ] **Step 6: Create `PatternController`**

`src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternController.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
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
    public List<PatternSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public PatternResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<PatternResponse> create(@Valid @RequestBody PatternRequest request) {
        PatternResponse created = service.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }
}
```

- [ ] **Step 7: Create `ApiExceptionHandler`**

`src/main/java/vasconcelos/silvio/crochet_back/config/ApiExceptionHandler.java`:

```java
package vasconcelos.silvio.crochet_back.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vasconcelos.silvio.crochet_back.pattern.PatternNotFoundException;

import java.util.List;
import java.util.Map;

/**
 * Maps errors to RFC 9457 problem details. The base class already turns malformed JSON and
 * path-variable type mismatches into 400 problems.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(PatternNotFoundException.class)
    ProblemDetail handleNotFound(PatternNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test`
Expected: PASS — all tests, including every `invalidRequests` case.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back src/test/java/vasconcelos/silvio/crochet_back/pattern
git commit -m "feat: add list, get and create pattern endpoints with problem details"
```

---

### Task 5: Replace (PUT) and delete endpoints

**Files:**
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternService.java` (add `replace`, `delete`)
- Modify: `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternController.java` (add `PUT`, `DELETE`)
- Test: `src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternWriteApiTests.java`

**Interfaces:**
- Consumes: `PatternService.find(long)` (private, Task 4), `PatternMapper.applyFields`, `PatternMapper.toSteps`, `PatternMapper.toResponse` (Task 3), `Pattern.getSteps()`, `Pattern.addStep`, `Pattern.touch()` (Task 2), `PatternJson.CHAT`, `PatternJson.create`, `PatternJson.withSteps` (Task 4).
- Produces: `PatternResponse PatternService.replace(long id, PatternRequest)`, `void PatternService.delete(long id)`; `PUT /api/patterns/{id}` → 200, `DELETE /api/patterns/{id}` → 204.

- [ ] **Step 1: Write the failing tests**

`src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternWriteApiTests.java`:

```java
package vasconcelos.silvio.crochet_back.pattern;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.CHAT;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.create;
import static vasconcelos.silvio.crochet_back.pattern.PatternJson.withSteps;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PatternWriteApiTests {

    private static final String ONE_STEP = withSteps("\"title\": \"Chat v2\", \"yarn\": \"Laine\",", """
            [ { "title": "Unique", "rows": [
                { "number": "1", "value": "8ms (8)" },
                { "number": "2", "value": "8aug (16)" } ] } ]""");

    private static final String THREE_STEPS = withSteps("\"title\": \"Chat v3\",", """
            [ { "title": "S1", "rows": [ { "number": "1", "value": "a" } ] },
              { "title": "S2", "rows": [ { "number": "1", "value": "b" } ] },
              { "title": "S3", "rows": [ { "number": "1", "value": "c" } ] } ]""");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE pattern RESTART IDENTITY CASCADE");
    }

    @Test
    void putReplacesFieldsAndTree() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(ONE_STEP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Chat v2"));

        mvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Chat v2"))
                .andExpect(jsonPath("$.yarn").value("Laine"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.hookSizeMm").doesNotExist())
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].title").value("Unique"))
                .andExpect(jsonPath("$.steps[0].rows[0].value").value("8ms (8)"))
                .andExpect(jsonPath("$.steps[0].rows[1].value").value("8aug (16)"));

        assertThat(count("step")).isEqualTo(1);
        assertThat(count("pattern_row")).isEqualTo(2);
    }

    @Test
    void putWithMoreStepsThanBeforeSucceeds() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(THREE_STEPS))
                .andExpect(status().isOk());

        mvc.perform(get(location))
                .andExpect(jsonPath("$.steps[0].title").value("S1"))
                .andExpect(jsonPath("$.steps[1].title").value("S2"))
                .andExpect(jsonPath("$.steps[2].title").value("S3"));
        assertThat(count("step")).isEqualTo(3);
        assertThat(count("pattern_row")).isEqualTo(3);
    }

    @Test
    void putChangingOnlyRowsBumpsUpdatedAt() throws Exception {
        String location = create(mvc, CHAT);
        String before = mvc.perform(get(location)).andReturn().getResponse().getContentAsString();

        String sameFieldsOtherRow = CHAT.replace("34ms (34)", "34ms en spirale (34)");
        String after = mvc.perform(put(location).contentType(APPLICATION_JSON).content(sameFieldsOtherRow))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(instant(after, "$.createdAt")).isEqualTo(instant(before, "$.createdAt"));
        assertThat(instant(after, "$.updatedAt")).isAfter(instant(before, "$.updatedAt"));
        assertThat((String) JsonPath.read(after, "$.steps[1].rows[0].value")).isEqualTo("34ms en spirale (34)");
    }

    @Test
    void invalidPutLeavesPatternUnchanged() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(put(location).contentType(APPLICATION_JSON).content(withSteps("\"title\": \"\",", "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));

        mvc.perform(get(location))
                .andExpect(jsonPath("$.title").value("Amigurumi Chat"))
                .andExpect(jsonPath("$.steps.length()").value(2));
    }

    @Test
    void putUnknownIdIsNotFound() throws Exception {
        mvc.perform(put("/api/patterns/999").contentType(APPLICATION_JSON).content(ONE_STEP))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    @Test
    void deleteRemovesPatternStepsAndRows() throws Exception {
        String location = create(mvc, CHAT);

        mvc.perform(delete(location)).andExpect(status().isNoContent());

        mvc.perform(get(location)).andExpect(status().isNotFound());
        assertThat(count("pattern")).isZero();
        assertThat(count("step")).isZero();
        assertThat(count("pattern_row")).isZero();
    }

    @Test
    void deleteUnknownIdIsNotFound() throws Exception {
        mvc.perform(delete("/api/patterns/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static Instant instant(String json, String path) {
        return Instant.parse(JsonPath.read(json, path));
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -Dtest=PatternWriteApiTests`
Expected: FAIL — PUT and DELETE return 405 (`HttpRequestMethodNotSupportedException`) instead of 200/204/404. `invalidPutLeavesPatternUnchanged` also fails (405 ≠ 400).

- [ ] **Step 3: Add `replace` and `delete` to `PatternService`**

In `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternService.java`, add these methods after `create`:

```java
    @Transactional
    public PatternResponse replace(long id, PatternRequest request) {
        Pattern pattern = find(id);
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
    public void delete(long id) {
        repository.delete(find(id));
    }
```

- [ ] **Step 4: Add the endpoints to `PatternController`**

In `src/main/java/vasconcelos/silvio/crochet_back/pattern/PatternController.java`, add imports:

```java
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
```

and add after `create`:

```java
    @PutMapping("/{id}")
    public PatternResponse replace(@PathVariable long id, @Valid @RequestBody PatternRequest request) {
        return service.replace(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test`
Expected: PASS — all tests. If `putWithMoreStepsThanBeforeSucceeds` or `putReplacesFieldsAndTree` fails with `duplicate key value violates unique constraint "uk_step_pattern_position"`, the first `repository.flush()` in `replace` was removed or moved. Restore it.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/pattern src/test/java/vasconcelos/silvio/crochet_back/pattern/PatternWriteApiTests.java
git commit -m "feat: add replace and delete pattern endpoints"
```

---

### Task 6: CORS configuration

**Files:**
- Create: `src/main/java/vasconcelos/silvio/crochet_back/config/CorsProperties.java`
- Create: `src/main/java/vasconcelos/silvio/crochet_back/config/CorsConfig.java`
- Modify: `src/main/resources/application.yaml` (add `crochet.cors`)
- Test: `src/test/java/vasconcelos/silvio/crochet_back/config/CorsTests.java`

**Interfaces:**
- Consumes: `/api/patterns` endpoints (Task 4), `TestcontainersConfiguration` (Task 1).
- Produces: `record CorsProperties(List<String> allowedOrigins)` bound to `crochet.cors`; CORS on `/api/**` for methods GET, POST, PUT, DELETE.

- [ ] **Step 1: Write the failing test**

`src/test/java/vasconcelos/silvio/crochet_back/config/CorsTests.java`:

```java
package vasconcelos.silvio.crochet_back.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import vasconcelos.silvio.crochet_back.TestcontainersConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsTests {

    @Autowired
    MockMvc mvc;

    @Test
    void preflightFromConfiguredOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/patterns/1")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void preflightFromOtherOriginIsRejected() throws Exception {
        mvc.perform(options("/api/patterns")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw test -Dtest=CorsTests`
Expected: FAIL — `preflightFromConfiguredOriginIsAllowed` gets 403, since no CORS mapping exists yet.

- [ ] **Step 3: Create `CorsProperties`**

`src/main/java/vasconcelos/silvio/crochet_back/config/CorsProperties.java`:

```java
package vasconcelos.silvio.crochet_back.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("crochet.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
```

- [ ] **Step 4: Create `CorsConfig`**

`src/main/java/vasconcelos/silvio/crochet_back/config/CorsConfig.java`:

```java
package vasconcelos.silvio.crochet_back.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private final CorsProperties properties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
```

- [ ] **Step 5: Add the property to `application.yaml`**

Append at the end of `src/main/resources/application.yaml` (top level, not under `spring:`):

```yaml
crochet:
  cors:
    allowed-origins:
      - http://localhost:5173
```

- [ ] **Step 6: Run the whole suite to verify it passes**

Run: `./mvnw test`
Expected: PASS — all test classes green (`CrochetBackApplicationTests`, `SchemaMigrationTests`, `PatternRepositoryTests`, `PatternMapperTest`, `PatternApiTests`, `PatternWriteApiTests`, `CorsTests`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/vasconcelos/silvio/crochet_back/config src/main/resources/application.yaml src/test/java/vasconcelos/silvio/crochet_back/config
git commit -m "feat: add configurable CORS for the API"
```

- [ ] **Step 8: Manual smoke check against the compose database**

```bash
docker compose up -d
./mvnw spring-boot:run
```

In another terminal:

```bash
curl -s -X POST localhost:8080/api/patterns -H 'Content-Type: application/json' \
  -d '{"title":"Test","steps":[{"title":"A","rows":[{"number":"1","value":"6ms (6)"}]}]}'
curl -s localhost:8080/api/patterns
```

Expected: the POST returns the created pattern with an `id`; the GET returns a one-element array with `"stepCount":1`. Stop the app afterwards (Ctrl+C) and run `docker compose down`.
