package com.miniim.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayAutoRepairConfigTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(FlywayAutoRepairConfig.class);

    @Test
    void createsStrategyByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(FlywayMigrationStrategy.class));
    }

    @Test
    void canDisableByProperty() {
        contextRunner
                .withPropertyValues("im.flyway.auto-repair=false")
                .run(context -> assertThat(context).doesNotHaveBean(FlywayMigrationStrategy.class));
    }
}

