package com.home.sourcedata.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class SourceDataMigrationRunnerTest {
    @Test
    void parsesExplicitOperationAndConfirmation() {
        assertThat(SourceDataMigrationRunner.parse(new String[] {"--operation=migrate", "--target=3", "--confirm=3"}))
                .containsEntry("operation", "migrate")
                .containsEntry("target", "3");
    }

    @Test
    void ownsTheSupportedOperationsAsAnInternalEnum() {
        assertThatCode(() -> Class.forName("com.home.sourcedata.migration.SourceDataMigrationRunner$Operation"))
                .doesNotThrowAnyException();
    }
}
