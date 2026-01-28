package com.miniim.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "im.flyway.auto-repair", havingValue = "true", matchIfMissing = true)
public class FlywayAutoRepairConfig {
    private static final Logger log = LoggerFactory.getLogger(FlywayAutoRepairConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return new FlywayMigrationStrategy() {
            @Override
            public void migrate(Flyway flyway) {
                try {
                    flyway.validate();
                } catch (FlywayValidateException e) {
                    log.warn("Flyway: validate failed, running repair() and retry migrate()", e);
                    try {
                        flyway.repair();
                    } catch (Exception repairError) {
                        log.warn("Flyway: repair() failed, continue migrate()", repairError);
                    }
                } catch (Exception e) {
                    log.warn("Flyway: validate() failed, continue migrate()", e);
                }
                flyway.migrate();
            }
        };
    }
}
