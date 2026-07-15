package com.home.admin.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AdminOpsRunnerTest {
    @Test
    void rejectsPasswordCommandArgument() {
        assertThatThrownBy(
                        () -> AdminOpsRunner.parse(new String[] {"--operation=create-account", "--password=forbidden"}))
                .isInstanceOf(AdminOpsRunner.UsageException.class)
                .hasMessage("password command arguments are forbidden");
        assertThatThrownBy(() ->
                        AdminOpsRunner.parse(new String[] {"--operation=create-account", "--operation=set-password"}))
                .isInstanceOf(AdminOpsRunner.UsageException.class)
                .hasMessage("duplicate option");
    }

    @Test
    void readsPasswordFromStandardInputAndRejectsBlankInput() {
        InputStream original = System.in;
        try {
            System.setIn(new ByteArrayInputStream("stdin-password\n".getBytes(StandardCharsets.UTF_8)));
            assertThat(new StandardInputAdminPasswordSource().read()).isEqualTo("stdin-password");

            System.setIn(new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> new StandardInputAdminPasswordSource().read())
                    .isInstanceOf(AdminOpsRunner.UsageException.class)
                    .hasMessage("password must be provided through stdin or ADMIN_OPS_PASSWORD");
        } finally {
            System.setIn(original);
        }
    }
}
