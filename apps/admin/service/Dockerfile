FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY apps/admin/service apps/admin/service
COPY apps/property-data/gradlew apps/property-data/gradlew
COPY apps/property-data/gradle apps/property-data/gradle
COPY libs/security-jwt-core libs/security-jwt-core
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    cd apps/admin/service \
    && chmod +x gradlew \
    && ./gradlew :api:bootJar :api:verifyRuntimeMigrationBoundary :migration:bootJar :ops:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS runtime
RUN groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search
WORKDIR /app
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]

FROM runtime AS api
COPY --from=build --chown=10001:10001 /workspace/apps/admin/service/api/build/libs/admin-service-api.jar /app/application.jar
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD timeout 3 bash -c '</dev/tcp/127.0.0.1/8081' || exit 1

FROM runtime AS migration
COPY --from=build --chown=10001:10001 /workspace/apps/admin/service/migration/build/libs/admin-service-migration.jar /app/application.jar

FROM runtime AS ops
COPY --from=build --chown=10001:10001 /workspace/apps/admin/service/ops/build/libs/admin-service-ops.jar /app/application.jar
