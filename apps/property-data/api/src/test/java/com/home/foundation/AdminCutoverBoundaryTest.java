package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminCutoverBoundaryTest {
    @Test
    @DisplayName("property-data runtime은 internal JWT admin HTTP surface만 포함한다")
    void propertyRuntimeContainsOnlyInternalJwtAdminHttpSurface() throws Exception {
        Path current = Path.of(System.getProperty("user.dir"));
        Path project = Files.isDirectory(current.resolve("api/src/main")) ? current : current.getParent();
        List<String> violations = new ArrayList<>();
        for (Path root : List.of(project.resolve("api/src/main"), project.resolve("core/src/main"))) {
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String content = Files.readString(file);
                    if (content.contains("X-Admin-" + "Access-Code")
                            || content.contains("access-" + "code")
                            || content.contains("/api/v1/admin/")) {
                        violations.add(project.relativize(file).toString());
                    }
                }
            }
        }
        assertThat(violations)
                .as("legacy browser-facing admin authentication sources")
                .isEmpty();
    }
}
