# Retrieval eval: retrieval-128-recheck

2026-10-29T02:17:42Z · 61 answer questions · embedding: `all-MiniLM-L6-v2 (local ONNX)` · docs: `Boot 4.1.1, 10 pages` · maxWordsPerChunk: `200` · embeddingWindowTokens: `128 (model default)`

Rank = position of the chunk containing the question's `expectedText` in the search results (searchDocs shows Claude the top 5 by default, at most 8). No model calls: the same data gives the same ranks.

## Scorecard

| Query set | hit@5 | hit@8 | MRR |
|---|---|---|---|
| Golden question text | 48/61 (79%) | 51/61 (84%) | 0.57 |
| Claude's queries, replayed from `2026-10-21T150512Z-golden-73-run2.json` | 53/61 (87%) | 59/61 (97%) | 0.68 |

## Not in the top 5 for the question text (13)

### testcontainers-service-connection: rank >20

- expected: [@ServiceConnection static Neo4jContainer neo4j]
- top 5 instead: [testing.testcontainers#0, testing.testcontainers.spring-beans#0, testing.spring-boot-applications.autoconfigured-jooq#0, testing.testcontainers.junit-extension#0, testing.testcontainers.importing-configuration-interfaces#0]
- with Claude's queries [Testcontainers @ServiceConnection database integration tests, @DynamicPropertySource container URL]: rank 7

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
- with Claude's queries [@DataJpaTest use real database instead of embedded AutoConfigureTestDatabase replace, Auto-configured Data JPA Tests AutoConfigureTestDatabase Replace.NONE]: rank 1

### transactional-random-port-no-rollback: rank 7

- expected: [the HTTP client and server run in separate threads and, thus, in separate transactions]
- top 5 instead: [testing.spring-boot-applications.with-running-server#0, testing.spring-boot-applications.with-mock-environment#0, testing.spring-boot-applications#2, testing.spring-boot-applications.with-running-server#2, testing.spring-boot-applications.with-running-server#1]
- with Claude's queries [@SpringBootTest RANDOM_PORT @Transactional rollback not applied HTTP server, test-managed transaction rollback @Transactional server separate thread]: rank 12

### multiple-slices-one-test: rank 9

- expected: [Including multiple "slices" by using several `@...Test` annotations in one test is not supported.]
- top 5 instead: [testing.spring-boot-applications.spring-mvc-tests#0, testing.spring-boot-applications.spring-mvc-tests#1, testing.spring-boot-applications.user-configuration-and-slicing#1, testing.spring-boot-applications.with-mock-environment#0, testing.spring-boot-applications.autoconfigured-spring-data-jpa#1]
- with Claude's queries [combining @WebMvcTest and @DataJpaTest on same test class, multiple @...Test slice annotations auto-configuration only one]: rank 1

### service-connection-subset-types: rank 10

- expected: [If you want to create only a subset of the applicable types, you can use the `type` attribute of `@ServiceConnection`.]
- top 5 instead: [data.sql.r2dbc#1, data.sql.datasource.lazy-connection#0, data.sql.r2dbc#0, data.sql.jdbc-client#0, data.sql.datasource.embedded#1]
- with Claude's queries [@ServiceConnection restrict connection details types PostgreSQLContainer JDBC R2DBC, @ServiceConnection type attribute limit connection details types]: rank 6

### webclient-test-slice: rank 10

- expected: [use the `@WebClientTest` annotation from the `spring-boot-webclient-test` module]
- top 5 instead: [testing.spring-boot-applications.autoconfigured-webservices.server#0, testing.spring-boot-applications.autoconfigured-webservices.client#0, testing.spring-boot-applications.autoconfigured-spring-restdocs.with-web-test-client#1, testing.spring-boot-applications.customizing-rest-test-client#0, testing.spring-boot-applications.autoconfigured-rest-client#0]
- with Claude's queries [@RestClientTest WebClient test slice, testing code that uses WebClient, MockWebServer, auto-configured tests list]: rank 5

### data-jpa-test-with-testcontainers: rank >20

- expected: [@AutoConfigureTestDatabase(replace = Replace.NONE), connection details can be automatically created for a service running in a container by annotating the container field in the test class]
- top 5 instead: [testing.spring-boot-applications.autoconfigured-spring-data-jdbc#0, testing.spring-boot-applications.autoconfigured-jdbc#0, testing.spring-boot-applications.autoconfigured-spring-data-r2dbc#0, testing.spring-boot-applications.autoconfigured-spring-data-jpa#0, testing.spring-boot-applications.autoconfigured-spring-data-jpa#1]
- with Claude's queries [@DataJpaTest Testcontainers PostgreSQL instead of embedded database, Testcontainers @ServiceConnection PostgreSQLContainer]: rank 5

### jpa-deferred-bootstrap: rank 11

- expected: [set the `spring.data.jpa.repositories.bootstrap-mode` property to `deferred` or `lazy`, You can use `SmartInitializingSingleton` to invoke any initialization that requires the JPA infrastructure.]
- top 5 instead: [data.sql.jpa-and-spring-data.repositories#1, testing.spring-boot-applications.autoconfigured-spring-data-jpa#0, data.sql.jdbc#0, data#0, data.sql.jpa-and-spring-data.entity-classes#0]
- with Claude's queries [Spring Data JPA repositories bootstrap mode background, spring.data.jpa.repositories.bootstrap-mode deferred lazy applicationTaskExecutor]: rank 8

### redis-template-override: rank 9

- expected: [when the exclusion is based on the bean name, `redisTemplate`, not its type]
- top 5 instead: [data.nosql.redis.connecting#0, testing.spring-boot-applications.autoconfigured-spring-data-redis#0, data.nosql.redis.receiving#1, data.nosql.redis.receiving#0, data.nosql.redis#0]
- with Claude's queries [RedisTemplate auto-configuration @ConditionalOnMissingBean redisTemplate bean name]: rank 8

### mockito-bean-reset-partial: rank 7

- expected: [Often, `@WebMvcTest` is limited to a single controller and is used in combination with `@MockitoBean`]
- top 5 instead: [testing.spring-boot-applications.spring-mvc-tests#1, testing.spring-boot-applications.with-mock-environment#1, testing.spring-boot-applications.with-mock-environment#0, testing.spring-boot-applications.autoconfigured-spring-restdocs.with-web-test-client#1, testing.spring-boot-applications.autoconfigured-spring-restdocs.with-mock-mvc#1]
- with Claude's queries [@WebMvcTest mock collaborator @MockitoBean, MockitoBean reset mock between tests, MockitoBean reset attribute MockReset]: rank 4

## All questions

| Question | Rank (question text) | Rank (Claude's queries) |
|---|---|---|
| testcontainers-service-connection | >20 | 7 |
| data-jpa-test-boot4 | 7 | 6 |
| dynamic-property-source-testing | 4 | 1 |
| mockito-bean-replacement | 4 | 1 |
| spy-existing-bean | 1 | 1 |
| web-slice-testing | >20 | 2 |
| rest-docs-integration | 2 | 1 |
| rest-test-client-boot4 | 3 | 6 |
| test-web-environment-random-port | 1 | 2 |
| test-rest-template-usage | 18 | 1 |
| test-configuration-beans | 2 | 3 |
| jdbc-slice-test | 2 | 2 |
| json-slice-testing | 1 | 3 |
| auto-configure-test-database | 12 | 1 |
| capture-console-output | 1 | 1 |
| embedded-database-per-context | 2 | 3 |
| connection-pooling-default | 1 | 1 |
| testcontainers-as-beans | 5 | 2 |
| service-connection-redis-boot4 | 2 | 1 |
| rest-client-test-slice | 1 | 1 |
| transactional-random-port-no-rollback | 7 | 12 |
| data-r2dbc-test-not-transactional | 1 | 2 |
| data-jpa-show-sql | 1 | 1 |
| test-entity-manager-outside-slice | 1 | 1 |
| slice-extra-autoconfiguration | 2 | 1 |
| multiple-slices-one-test | 9 | 1 |
| use-main-method | 2 | 1 |
| metrics-in-integration-tests | 1 | 1 |
| webflux-test-security-chain | 2 | 1 |
| webmvc-test-skips-configuration-beans | 4 | 13 |
| service-connection-custom-image | 5 | 1 |
| service-connection-subset-types | 10 | 6 |
| testcontainers-ssl-client-side | 1 | 1 |
| data-ldap-real-server | 2 | 4 |
| json-number-assertions | 2 | 2 |
| json-testers-without-jsontest | 1 | 1 |
| graphql-test-slice | 1 | 1 |
| webclient-test-slice | 10 | 5 |
| r2dbc-unique-embedded-database | 1 | 3 |
| lazy-connection-fetch | 1 | 1 |
| r2dbc-keeps-jdbc-datasource | 1 | 1 |
| test-rest-template-timeouts | 4 | 1 |
| config-data-initializer-value | 1 | 1 |
| component-scan-breaks-slices | 1 | 1 |
| testcontainers-cached-context-stopped | 1 | 2 |
| service-connection-generic-container-bean | 4 | 2 |
| rabbit-streams-service-connection | 2 | 2 |
| service-connection-vs-dynamic-properties | 4 | 3 |
| data-jpa-test-with-testcontainers | >20 | 5 |
| graphql-http-tester-mockmvc | 3 | 3 |
| rest-client-test-full-uri | 1 | 1 |
| test-configuration-ordering | 2 | 2 |
| jpa-deferred-bootstrap | 11 | 8 |
| redis-template-override | 9 | 8 |
| named-parameter-jdbc-template-missing | 2 | 2 |
| webmvc-test-module-boot4 | 3 | 1 |
| test-rest-template-restclient-dependency | 1 | 1 |
| mockbean-boot4 | 1 | 1 |
| jsontest-jackson-boot4 | 1 | 1 |
| kafka-testcontainer-partial | 1 | 1 |
| mockito-bean-reset-partial | 7 | 4 |
