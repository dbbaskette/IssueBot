package com.dbbaskette.issuebot.repository;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@TestPropertySource(properties = {
        "issuebot.github.token=test-token",
        "spring.flyway.enabled=true"
})
class DecompositionGroupMigrationTest {

    @Autowired private DataSource dataSource;

    @Test
    void createsDurableGroupTablesAndReconciliationColumns() throws Exception {
        DatabaseMetaData metadata = dataSource.getConnection().getMetaData();
        assertThat(exists(metadata.getTables(null, null, "DECOMPOSITION_GROUPS", null))).isTrue();
        assertThat(exists(metadata.getTables(null, null, "DECOMPOSITION_CHILDREN", null))).isTrue();
        assertThat(exists(metadata.getColumns(null, null, "DECOMPOSITION_GROUPS", "ATTENTION_REASON"))).isTrue();
        assertThat(exists(metadata.getColumns(null, null, "DECOMPOSITION_GROUPS", "LAST_ERROR"))).isTrue();
        assertThat(exists(metadata.getColumns(null, null, "DECOMPOSITION_GROUPS", "VERSION"))).isTrue();
    }

    private boolean exists(ResultSet rows) throws Exception {
        try (rows) {
            return rows.next();
        }
    }
}
