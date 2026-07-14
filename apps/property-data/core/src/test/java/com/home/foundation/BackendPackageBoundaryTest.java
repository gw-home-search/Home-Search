package com.home.foundation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.boundaryfixture.ForbiddenApplicationFixture;
import com.home.domain.boundaryfixture.ForbiddenDomainFixture;
import com.home.infrastructure.persistence.boundaryfixture.ForbiddenJpaPersistenceFixture;
import com.home.infrastructure.persistence.boundaryfixture.JdbcPersistenceFixture;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendPackageBoundaryTest {

    private static final Path CORE_MAIN_CLASSES = Path.of("build/classes/java/main");

    private static final ArchRule DOMAIN_BOUNDARY = noClasses()
            .that()
            .resideInAPackage("com.home.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.home.application..",
                    "com.home.infrastructure..",
                    "org.springframework..",
                    "java.sql..",
                    "jakarta.persistence..",
                    "javax.persistence..");

    private static final ArchRule APPLICATION_BOUNDARY = noClasses()
            .that()
            .resideInAPackage("com.home.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.home.infrastructure..",
                    "org.springframework.jdbc..",
                    "org.springframework.web..",
                    "jakarta.persistence..",
                    "javax.persistence..");

    private static final ArchRule JDBC_PERSISTENCE_BOUNDARY = noClasses()
            .that()
            .resideInAPackage("com.home.infrastructure.persistence..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "javax.persistence..", "org.hibernate..");

    private static final ArchRule EXTERNAL_ADAPTER_BOUNDARY = noClasses()
            .that()
            .resideInAPackage("com.home.infrastructure.external..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.home.infrastructure.persistence..");

    @Test
    @DisplayName("현재 property-data core의 계층 경계가 유지된다")
    void currentPropertyDataCoreRespectsLayerBoundaries() {
        JavaClasses productionClasses = new ClassFileImporter().importPath(CORE_MAIN_CLASSES);

        DOMAIN_BOUNDARY.check(productionClasses);
        APPLICATION_BOUNDARY.check(productionClasses);
        JDBC_PERSISTENCE_BOUNDARY.check(productionClasses);
        EXTERNAL_ADAPTER_BOUNDARY.check(productionClasses);
    }

    @Test
    @DisplayName("domain과 application의 forbidden dependency fixture를 거부한다")
    void roleRulesRejectForbiddenDependencyFixtures() {
        JavaClasses domainFixture = new ClassFileImporter().importClasses(ForbiddenDomainFixture.class);
        JavaClasses applicationFixture = new ClassFileImporter().importClasses(ForbiddenApplicationFixture.class);

        assertThatThrownBy(() -> DOMAIN_BOUNDARY.check(domainFixture)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> APPLICATION_BOUNDARY.check(applicationFixture)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("현재 persistence는 JDBC를 허용하지만 JPA fixture는 거부한다")
    void persistenceAllowsJdbcButRejectsJpaFixture() {
        JavaClasses jdbcFixture = new ClassFileImporter().importClasses(JdbcPersistenceFixture.class);
        JavaClasses jpaFixture = new ClassFileImporter().importClasses(ForbiddenJpaPersistenceFixture.class);

        assertThatCode(() -> JDBC_PERSISTENCE_BOUNDARY.check(jdbcFixture)).doesNotThrowAnyException();
        assertThatThrownBy(() -> JDBC_PERSISTENCE_BOUNDARY.check(jpaFixture)).isInstanceOf(AssertionError.class);
    }
}
