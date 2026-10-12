package com.tw.docsagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The context starts against real pgvector, Flyway applies V1, and Spring AI's schema validation
 * accepts the Flyway-owned vector_store table (it would fail startup otherwise).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaIT {

    @Autowired
    JdbcClient jdbc;

    @Test
    void flywayCreatesPgvectorSchema() {
        assertThat(jdbc.sql("select extname from pg_extension where extname = 'vector'")
                .query(String.class).optional())
                .contains("vector");

        assertThat(jdbc.sql("select success from flyway_schema_history where version = '1'")
                .query(Boolean.class).single())
                .isTrue();

        // vector(384): pgvector stores the dimension count in atttypmod
        assertThat(jdbc.sql("""
                        select atttypmod from pg_attribute
                        where attrelid = 'vector_store'::regclass and attname = 'embedding'""")
                .query(Integer.class).single())
                .isEqualTo(384);
    }
}
