package com.home.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminMigrationRunnerTest {
    @Test void parsesExplicitMigrationConfirmation() {
        assertThat(AdminMigrationRunner.parse(new String[] {"--operation=migrate", "--target=1", "--confirm=1"}))
            .containsEntry("operation", "migrate").containsEntry("target", "1");
    }
}
