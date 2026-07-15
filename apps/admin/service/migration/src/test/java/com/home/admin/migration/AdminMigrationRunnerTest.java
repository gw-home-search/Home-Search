package com.home.admin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdminMigrationRunnerTest {
    @Test
    void parsesExplicitMigrationConfirmation() {
        assertThat(AdminMigrationRunner.parse(new String[] {"--operation=migrate", "--target=1", "--confirm=1"}))
                .containsEntry("operation", "migrate")
                .containsEntry("target", "1");
        assertThat(AdminMigrationRunner.parse(new String[] {"ignored", "--operation=info"}))
                .containsOnlyKeys("operation");
        assertThatThrownBy(() -> AdminMigrationRunner.parse(new String[] {"--operation=info", "--operation=validate"}))
                .isInstanceOf(AdminMigrationRunner.UsageException.class)
                .hasMessage("duplicate option");
    }
}
