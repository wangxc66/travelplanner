package com.laioffer.travelplanner.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A blank database must be migrated before Hibernate validates the entity mappings. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:migration_validation;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "travelplanner.h2.tcp.enabled=false"
})
@ActiveProfiles("migration-test")
class DatabaseMigrationIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void cleanDatabaseMigratesAndHibernateContextStarts() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");

        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'
                """, String.class);
        assertThat(tables).contains("APP_USER", "CITY", "POI", "TRIP", "ITINERARY_ITEM",
                "flyway_schema_history");

        Integer indexCount = jdbc.queryForObject("""
                select count(*) from information_schema.indexes
                where index_name = 'IDX_ITINERARY_TRIP_DAY_SEQ'
                """, Integer.class);
        assertThat(indexCount).isEqualTo(1);

        List<String> constraints = jdbc.queryForList("""
                select constraint_name from information_schema.table_constraints
                where constraint_schema = 'PUBLIC'
                """, String.class);
        assertThat(constraints).contains(
                "FK_ITINERARY_ITEM_TRIP",
                "UK_ITINERARY_ITEM_TRIP_POI",
                "UK_ITINERARY_ITEM_TRIP_DAY_SEQ",
                "UK_APP_USER_USERNAME",
                "CK_TRIP_NUM_DAYS");

        // The migration-test profile proves schema creation is independent from demo catalog data.
        assertThat(jdbc.queryForObject("select count(*) from city", Long.class)).isZero();
    }
}
