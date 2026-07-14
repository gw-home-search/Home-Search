package com.home.admin.ops;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AdminOpsRunnerTest {
    @Test
    void rejectsPasswordCommandArgument() {
        assertThatThrownBy(
                        () -> AdminOpsRunner.parse(new String[] {"--operation=create-account", "--password=forbidden"}))
                .isInstanceOf(AdminOpsRunner.UsageException.class)
                .hasMessage("password command arguments are forbidden");
    }
}
