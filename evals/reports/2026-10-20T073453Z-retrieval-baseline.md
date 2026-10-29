# Retrieval eval: retrieval-baseline

2026-10-20T07:34:53Z · 20 answer questions · embedding: `all-MiniLM-L6-v2 (local ONNX)` · docs: `Boot 4.1.1, 10 pages` · maxWordsPerChunk: `200`

Rank = position of the chunk containing the question's `expectedText` in the search results (searchDocs shows Claude the top 5 by default, at most 8). No model calls: the same data gives the same ranks.

## Scorecard

| Query set | hit@5 | hit@8 | MRR |
|---|---|---|---|
| Golden question text | 15/20 (75%) | 16/20 (80%) | 0.49 |
| Claude's queries, replayed from `2026-10-20T065749Z-baseline-v2.json` | 18/20 (90%) | 20/20 (100%) | 0.70 |

## Not in the top 5 for the question text (5)

### testcontainers-service-connection: rank >20

- expected: [@ServiceConnection static Neo4jContainer neo4j]
- top 5 instead: [testing.testcontainers#0, testing.testcontainers.spring-beans#0, testing.spring-boot-applications.autoconfigured-jooq#0, testing.testcontainers.junit-extension#0, testing.testcontainers.importing-configuration-interfaces#0]
- with Claude's queries [Testcontainers @ServiceConnection database container integration tests, @DynamicPropertySource dynamic properties container]: rank 6

### data-jpa-test-boot4: rank 7

- expected: [`@DataJpaTest` annotation from the `spring-boot-data-jpa-test` module]
- top 5 instead: [testing.spring-boot-applications.autoconfigured-spring-data-jdbc#0, testing.spring-boot-applications.autoconfigured-spring-data-neo4j#0, testing.spring-boot-applications.autoconfigured-spring-data-r2dbc#0, data#0, testing.spring-boot-applications.autoconfigured-jdbc#0]
- with Claude's queries [@DataJpaTest auto-configuration and required module]: rank 6

### web-slice-testing: rank >20

- expected: [use the `@WebMvcTest` annotation from the `spring-boot-webmvc-test` module]
- top 5 instead: [testing.spring-boot-applications.with-mock-environment#0, testing.spring-boot-applications.autoconfigured-rest-client#1, testing.spring-boot-applications.autoconfigured-spring-restdocs.with-mock-mvc#1, testing.spring-boot-applications.with-mock-environment#1, testing.spring-boot-applications.autoconfigured-spring-restdocs.with-mock-mvc#0]
- with Claude's queries [@WebMvcTest MockMvc test controllers without full application context]: rank 2

### test-rest-template-usage: rank 18

- expected: [by not throwing exceptions on 4xx and 5xx errors]
- top 5 instead: [testing.spring-boot-applications.with-running-server#0, testing.spring-boot-applications.autoconfigured-rest-client#0, testing.spring-boot-applications.with-mock-environment#0, testing.test-modules#1, testing.spring-boot-applications.with-running-server#2]
- with Claude's queries [TestRestTemplate REST calls against running test server redirects]: rank 1

### auto-configure-test-database: rank 12

- expected: [@AutoConfigureTestDatabase(replace = Replace.NONE)]
- top 5 instead: [data.sql.datasource.embedded#0, testing.spring-boot-applications.autoconfigured-spring-data-jdbc#0, data.sql.datasource.embedded#1, data.sql.r2dbc.embedded#0, testing.spring-boot-applications.autoconfigured-jooq#0]
- with Claude's queries [@DataJpaTest replace embedded database with real database AutoConfigureTestDatabase, Auto-configured Data JPA Tests real database @AutoConfigureTestDatabase Replace.NONE]: rank 1

## All questions

| Question | Rank (question text) | Rank (Claude's queries) |
|---|---|---|
| testcontainers-service-connection | >20 | 6 |
| data-jpa-test-boot4 | 7 | 6 |
| dynamic-property-source-testing | 4 | 1 |
| mockito-bean-replacement | 4 | 1 |
| spy-existing-bean | 1 | 1 |
| web-slice-testing | >20 | 2 |
| rest-docs-integration | 2 | 1 |
| rest-test-client-boot4 | 3 | 4 |
| test-web-environment-random-port | 1 | 2 |
| test-rest-template-usage | 18 | 1 |
| test-configuration-beans | 2 | 3 |
| jdbc-slice-test | 2 | 1 |
| json-slice-testing | 1 | 3 |
| auto-configure-test-database | 12 | 1 |
| capture-console-output | 1 | 1 |
| embedded-database-per-context | 2 | 3 |
| connection-pooling-default | 1 | 1 |
| testcontainers-as-beans | 5 | 2 |
| service-connection-redis-boot4 | 2 | 1 |
| rest-client-test-slice | 1 | 1 |
