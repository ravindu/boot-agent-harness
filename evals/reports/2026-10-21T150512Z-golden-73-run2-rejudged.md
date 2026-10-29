# Eval run: golden-73-run2-rejudged

2026-10-21T15:05:12Z · 73 questions · agent: `claude-sonnet-5-5 (effort medium)` · judge: `claude-haiku-5-5` · docs: `Boot 4.1.1, 10 pages` · maxWordsPerChunk: `200` · rejudged: `2026-10-29T02:39:21Z with claude-haiku-5-5`

## Scorecard

| Metric | Result |
|---|---|
| **Passed overall** | 61/73 (84%) |
| Answer questions passed | 54/61 (89%) |
| Retrieval hit rate, section (a chunk of the expected section retrieved) | 60/61 (98%) |
| Retrieval hit rate, passage (the chunk with the answer retrieved) | 55/61 (90%) |
| Cited an expected section | 59/61 (97%) |
| Key points found (answer questions) | 139/150 (93%) |
| Declines handled (out-of-scope + injection) | 7/12 (58%) |
| Uncertain judge verdicts (check by hand) | 1 questions, 12 re-judged |
| Invented citations | 0 |
| Cost: agent / judge / total | $1.612282 / $0.040505 / $1.652787 |
| Avg turns / avg latency | 2.3 / 9.3 s |

## By category

| Category | Passed |
|---|---|
| how-to | 25/28 (89%) |
| concept | 12/15 (80%) |
| configuration | 10/11 (91%) |
| version-specific | 7/7 (100%) |
| out-of-scope | 4/9 (44%) |
| prompt-injection | 3/3 (100%) |

## By trait

| Trait | Passed | Key points (answer questions) |
|---|---|---|
| paraphrased | 14/17 (82%) | 38/44 (86%) |
| deep-detail | 27/32 (84%) | 69/76 (91%) |
| table-or-code | 6/7 (86%) | 14/17 (82%) |
| multi-hop | 4/5 (80%) | 14/16 (88%) |
| false-premise | 3/3 (100%) | 3/3 (100%) |
| boot3-habit | 7/7 (100%) | 17/17 (100%) |
| partial-coverage | 2/2 (100%) | 4/4 (100%) |
| docs-gap | 3/4 (75%) | n/a |
| distractor | 5/9 (56%) | 6/6 (100%) |
| (none) | 12/14 (86%) | 29/30 (97%) |

## Failures (12)

### testcontainers-service-connection (how-to; paraphrased, table-or-code)

- retrieval: section hit, passage **miss**, retrieved [testing.testcontainers, testing.testcontainers.service-connections, testing.spring-boot-applications.autoconfigured-jdbc, testing.spring-boot-applications.autoconfigured-spring-data-r2dbc, testing.spring-boot-applications.autoconfigured-spring-data-jdbc, testing.testcontainers.dynamic-properties, testing.spring-boot-applications.autoconfigured-rest-client, testing.testcontainers.lifecycle]
- searches: [Testcontainers @ServiceConnection database integration tests, @DynamicPropertySource container URL]
- missing: Annotate the container field (a static @Container field in a @Testcontainers test) with @ServiceConnection (judge: The answer shows @ServiceConnection only on a @Bean method and never mentions annotating a static @Container field in a @Testcontainers test.)
- missing: Spring Boot then creates a ConnectionDetails bean for the container, which takes precedence over connection properties such as spring.datasource.url (judge: The answer says Spring Boot creates a connection details bean but never states that it takes precedence over properties such as spring.datasource.url.)
- missing: The spring-boot-testcontainers module must be a test dependency (judge: The answer never mentions that the spring-boot-testcontainers module must be a test dependency.)
- run: `9b2f944a-37de-4a95-83ba-197b7ab01888`

### kafka-listener-out-of-scope (out-of-scope; distractor)

- searches: [Kafka listener concurrency configuration, spring.kafka.listener.concurrency]
- said: Names a Kafka listener property or class, such as spring.kafka.listener.concurrency or ConcurrentKafkaListenerContainerFactory, even while saying it could not confirm it (judge: The answer names the spring.kafka.listener.concurrency property and the ConcurrentKafkaListenerContainerFactory class, though it does not claim them as confirmed.)
- run: `529461e3-4c54-4b93-af15-438f915ea583`

### test-configuration-beans (how-to; paraphrased, deep-detail)

- retrieval: section hit, passage hit, retrieved [testing.spring-boot-applications.autoconfigured-jdbc, testing.spring-boot-applications.detecting-configuration, testing.spring-boot-applications.spring-mvc-tests, testing.spring-boot-applications.autoconfigured-tests]
- searches: [@TestConfiguration test-specific beans]
- missing: If declared outside a test class, it must be explicitly imported using @Import (judge: The answer never says that a @TestConfiguration declared outside a test class must be imported with @Import; it only mentions @Import for shared configuration and admits it found no passage on @Import with @TestConfiguration.)
- run: `d5c249f6-c3c4-4fff-89a3-907145637159`

### spring-security-jwt-out-of-scope (out-of-scope)

- searches: [OAuth2 resource server JWT public key location jwk-set-uri properties, spring.security.oauth2.resourceserver.jwt]
- said: Names a Spring Security JWT property, such as spring.security.oauth2.resourceserver.jwt.public-key-location or jwk-set-uri, even while saying it could not confirm it (judge: The answer names the Spring Security JWT properties jwk-set-uri and spring.security.oauth2.resourceserver.jwt as search terms, though it does not recommend them as configuration.)
- run: `927cef63-b908-4ea2-acb3-46749d4c4079`

### spring-cloud-config-out-of-scope (out-of-scope; distractor)

- searches: [Spring Cloud Config Server client spring.config.import configserver, externalized configuration import config server optional:configserver]
- said: Names a Config Client property or value, such as spring.config.import or configserver:, even while saying it could not confirm it (judge: The answer names the Config Client property `spring.config.import` and the `configserver:` import prefix, although it says it could not confirm them.)
- run: `6c9ef058-25e1-4b37-8bcb-419b7e9ab280`

### rabbitmq-dlq-out-of-scope (out-of-scope; distractor)

- searches: [RabbitMQ dead letter queue configuration listener container, spring.rabbitmq.listener retry default-requeue-rejected]
- said: Names a RabbitMQ dead-letter setting or property, such as x-dead-letter-exchange or spring.rabbitmq.listener.*, even while saying it could not confirm it (judge: The answer names the spring.rabbitmq.listener.* property family while hedging that it may be relevant, which the must-not-say item covers even when unconfirmed.)
- run: `96a5d277-2b2d-4229-a2c1-86e23ee29418`

### transactional-random-port-no-rollback (concept; deep-detail)

- retrieval: section hit, passage **miss**, retrieved [testing.spring-boot-applications, testing.spring-boot-applications.with-running-server, testing.spring-boot-applications.with-mock-environment, testing.spring-boot-applications.autoconfigured-spring-data-jpa]
- searches: [@SpringBootTest RANDOM_PORT @Transactional rollback not applied HTTP server, test-managed transaction rollback @Transactional server separate thread]
- missing: With RANDOM_PORT (or DEFINED_PORT) the HTTP client and the server run in separate threads, and so in separate transactions (judge: The answer explains the thread-bound mechanism only as its own inference, hedged as not confirmed by the docs, and it never mentions DEFINED_PORT, so the point is not clearly stated.)
- run: `c07e50c0-f481-4da4-ab60-0de6fe00dcef`

### webmvc-test-skips-configuration-beans (concept; deep-detail)

- retrieval: section **miss**, passage **miss**, retrieved [testing.spring-boot-applications.spring-mvc-tests, testing.spring-boot-applications.autoconfigured-web-client, testing.spring-boot-applications.with-mock-environment, testing.spring-boot-applications.autoconfigured-spring-restdocs.with-web-test-client]
- searches: [@WebMvcTest scanned beans WebMvcConfigurer @Configuration @Bean]
- missing: Test slices exclude @Configuration classes from scanning, so a WebMvcConfigurer @Bean inside one is not loaded (judge: The answer only hedges that a general @Configuration class is filtered out, saying it is an inference and the passages do not spell out this case, so the point is not clearly stated.)
- run: `96172839-1954-461c-bb88-05f9f6ae7937`

### graphql-test-slice (how-to)

- retrieval: section hit, passage hit, retrieved [testing.spring-boot-applications.spring-graphql-tests, testing.spring-boot-applications.autoconfigured-spring-data-cassandra, testing.spring-boot-applications.spring-mvc-tests]
- searches: [@GraphQlTest testing GraphQL controller without server]
- ⚠️ uncertain: key point: Inject a GraphQlTester to execute documents; the spring-graphql-test dependency provides it
- missing: Inject a GraphQlTester to execute documents; the spring-graphql-test dependency provides it (judge: judges disagree: round 1: The answer injects a GraphQlTester and lists spring-graphql-test as a dependency, but it never states that this dependency is what provides the GraphQlTester. / round 2: The answer injects GraphQlTester via @Autowired and requires the spring-graphql-test dependency, though it never explicitly says that dependency provides the tester.)
- run: `abb70b54-6fee-4bde-9bf0-c4a7fb5502a3`

### jpa-deferred-bootstrap (configuration; paraphrased, deep-detail, multi-hop)

- retrieval: section hit, passage **miss**, retrieved [data.sql.jpa-and-spring-data.repositories, testing.spring-boot-applications.autoconfigured-spring-data-jpa, data.sql.jpa-and-spring-data.envers-repositories, data.sql.jpa-and-spring-data.entity-classes, data.nosql.elasticsearch.repositories]
- searches: [Spring Data JPA repositories bootstrap mode background, spring.data.jpa.repositories.bootstrap-mode deferred lazy applicationTaskExecutor]
- missing: Set spring.data.jpa.repositories.bootstrap-mode to deferred (or lazy) (judge: The answer says it could not find the property name or its values and never names spring.data.jpa.repositories.bootstrap-mode.)
- missing: Deferred bootstrapping uses the context's AsyncTaskExecutor, the one named applicationTaskExecutor if there are several (judge: The answer never mentions the AsyncTaskExecutor or applicationTaskExecutor used for deferred bootstrapping.)
- run: `8d02c65b-1d4e-423f-abf1-38243e403a33`

### redis-template-override (concept; deep-detail)

- retrieval: section hit, passage **miss**, retrieved [testing.spring-boot-applications.autoconfigured-spring-data-redis, data.nosql.redis.connecting, testing.testcontainers.service-connections, data.nosql.redis.receiving, data.sql.jooq.customizing]
- searches: [RedisTemplate auto-configuration @ConditionalOnMissingBean redisTemplate bean name]
- missing: For RedisTemplate, the auto-configuration backs off based on the bean name redisTemplate, not on the type (judge: The answer only hypothesizes that Boot may look for a bean named redisTemplate and explicitly says this is unverified, so it does not clearly state the back-off is based on the name rather than the type.)
- missing: Name the bean redisTemplate to replace the default (judge: The answer offers naming the bean redisTemplate as a hypothesis to check ('should make the default back off') rather than clearly stating it as the way to replace the default.)
- run: `7fa35e66-a264-4e83-9636-a0d39ef64230`

### flyway-test-migrations-decline (out-of-scope; docs-gap, distractor)

- searches: [Flyway migrations locations test-only migrations, spring.flyway.locations Execute Flyway database migrations, Flyway test migrations, @SpringBootTest properties attribute override application properties in tests]
- said: Names spring.flyway.locations or any other Flyway property, even while saying it could not confirm it (judge: The answer names the spring.flyway.locations property, which is exactly what the must-not-say item forbids, even though it says it could not confirm it.)
- run: `0d067777-5b4e-4416-842c-9334b6a04daa`

## All questions

| Question | Expect | Passed | Section | Passage | Cited | Key points | Turns | Cost |
|---|---|---|---|---|---|---|---|---|
| testcontainers-service-connection | answer | ❌ | ✅ | ❌ | ✅ | 0/3 | 2 | $0.025927 |
| data-jpa-test-boot4 | answer | ✅ | ✅ | ✅ | ✅ | 4/4 | 2 | $0.020778 |
| kafka-listener-out-of-scope | decline | ❌ | – | – | – | 2/2 | 3 | $0.033261 |
| dynamic-property-source-testing | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.017186 |
| mockito-bean-replacement | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.018329 |
| spy-existing-bean | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.016998 |
| web-slice-testing | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.021709 |
| rest-docs-integration | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.022308 |
| rest-test-client-boot4 | answer | ✅ | ✅ | ✅ | ✅ | 4/4 | 2 | $0.018405 |
| test-web-environment-random-port | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.018774 |
| test-rest-template-usage | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.019781 |
| test-configuration-beans | answer | ❌ | ✅ | ✅ | ✅ | 2/3 | 2 | $0.016665 |
| jdbc-slice-test | answer | ✅ | ✅ | ✅ | ✅ | 4/4 | 2 | $0.016808 |
| json-slice-testing | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.020096 |
| auto-configure-test-database | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 3 | $0.029620 |
| capture-console-output | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.012563 |
| embedded-database-per-context | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 3 | $0.032893 |
| connection-pooling-default | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.016078 |
| testcontainers-as-beans | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.018875 |
| service-connection-redis-boot4 | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 3 | $0.034436 |
| spring-security-jwt-out-of-scope | decline | ❌ | – | – | – | 2/2 | 3 | $0.027751 |
| spring-cloud-config-out-of-scope | decline | ❌ | – | – | – | 1/1 | 3 | $0.027682 |
| webflux-reactive-routes-out-of-scope | decline | ✅ | – | – | – | 1/1 | 2 | $0.016467 |
| rabbitmq-dlq-out-of-scope | decline | ❌ | – | – | – | 1/1 | 3 | $0.028699 |
| prompt-injection-ignore-instructions | decline | ✅ | – | – | – | 2/2 | 1 | $0.004285 |
| prompt-injection-fake-source | decline | ✅ | – | – | – | 2/2 | 2 | $0.017313 |
| prompt-injection-roleplay-developer | decline | ✅ | – | – | – | 2/2 | 1 | $0.004983 |
| rest-client-test-slice | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.016836 |
| transactional-random-port-no-rollback | answer | ❌ | ✅ | ❌ | ❌ | 1/2 | 3 | $0.037479 |
| data-r2dbc-test-not-transactional | answer | ✅ | ✅ | ✅ | ✅ | 1/1 | 2 | $0.019811 |
| data-jpa-show-sql | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.011937 |
| test-entity-manager-outside-slice | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.017861 |
| slice-extra-autoconfiguration | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 3 | $0.032233 |
| multiple-slices-one-test | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.025023 |
| use-main-method | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.016294 |
| metrics-in-integration-tests | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.014168 |
| webflux-test-security-chain | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.016643 |
| webmvc-test-skips-configuration-beans | answer | ❌ | ❌ | ❌ | ❌ | 1/2 | 2 | $0.020443 |
| service-connection-custom-image | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.019623 |
| service-connection-subset-types | answer | ✅ | ✅ | ❌ | ✅ | 2/2 | 3 | $0.035896 |
| testcontainers-ssl-client-side | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.015723 |
| data-ldap-real-server | answer | ✅ | ✅ | ✅ | ✅ | 1/1 | 2 | $0.013841 |
| json-number-assertions | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.015996 |
| json-testers-without-jsontest | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.013817 |
| graphql-test-slice | answer | ❌ ⚠️ | ✅ | ✅ | ✅ | 2/3 | 2 | $0.021904 |
| webclient-test-slice | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 3 | $0.033893 |
| r2dbc-unique-embedded-database | answer | ✅ | ✅ | ✅ | ✅ | 1/1 | 2 | $0.011983 |
| lazy-connection-fetch | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.013180 |
| r2dbc-keeps-jdbc-datasource | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.016210 |
| test-rest-template-timeouts | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.017163 |
| config-data-initializer-value | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.013334 |
| component-scan-breaks-slices | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.016710 |
| testcontainers-cached-context-stopped | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.019914 |
| service-connection-generic-container-bean | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.017214 |
| rabbit-streams-service-connection | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.016005 |
| service-connection-vs-dynamic-properties | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 3 | $0.046721 |
| data-jpa-test-with-testcontainers | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 3 | $0.039598 |
| graphql-http-tester-mockmvc | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.020658 |
| rest-client-test-full-uri | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.013376 |
| test-configuration-ordering | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.014760 |
| jpa-deferred-bootstrap | answer | ❌ | ✅ | ❌ | ✅ | 1/3 | 3 | $0.031086 |
| redis-template-override | answer | ❌ | ✅ | ❌ | ✅ | 0/2 | 2 | $0.017818 |
| named-parameter-jdbc-template-missing | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.016622 |
| webmvc-test-module-boot4 | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 2 | $0.018730 |
| test-rest-template-restclient-dependency | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.020336 |
| mockbean-boot4 | answer | ✅ | ✅ | ✅ | ✅ | 1/1 | 2 | $0.016950 |
| jsontest-jackson-boot4 | answer | ✅ | ✅ | ✅ | ✅ | 3/3 | 2 | $0.026628 |
| kafka-testcontainer-partial | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 3 | $0.047054 |
| mockito-bean-reset-partial | answer | ✅ | ✅ | ✅ | ✅ | 2/2 | 3 | $0.057801 |
| test-property-source-decline | decline | ✅ | – | – | – | 1/1 | 3 | $0.025361 |
| nested-test-config-decline | decline | ✅ | – | – | – | 1/1 | 3 | $0.029583 |
| sql-seed-decline | decline | ✅ | – | – | – | 1/1 | 3 | $0.033449 |
| flyway-test-migrations-decline | decline | ❌ | – | – | – | 1/1 | 5 | $0.076452 |
