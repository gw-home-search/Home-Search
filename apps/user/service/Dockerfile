FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY apps/user/service apps/user/service
COPY apps/property-data/gradlew apps/property-data/gradlew
COPY apps/property-data/gradle apps/property-data/gradle
COPY libs/security-jwt-core libs/security-jwt-core
COPY libs/user-auth-contract libs/user-auth-contract
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    cd apps/user/service \
    && chmod +x gradlew \
    && ./gradlew :app:bootJar :worker:bootJar :app:verifyRuntimeMigrationBoundary --no-daemon

FROM eclipse-temurin:21-jre AS app
RUN groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/user/service/app/build/libs/user-service-app.jar /app/application.jar
USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD timeout 3 bash -c '</dev/tcp/127.0.0.1/8082' || exit 1
ENTRYPOINT ["java", "-jar", "/app/application.jar"]

FROM eclipse-temurin:21-jre AS worker
RUN groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/user/service/worker/build/libs/user-insight-worker.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]

FROM --platform=linux/amd64 redgate/flyway:12.4.0 AS flyway
USER root
RUN mkdir -p /flyway/sql /flyway/conf \
    && chown -R 10001:10001 /flyway/sql /flyway/conf
COPY --chown=10001:10001 apps/user/service/db/migration/user /flyway/sql
COPY --chown=10001:10001 apps/user/service/db/flyway.conf /flyway/conf/flyway.conf
USER 10001:10001
ENTRYPOINT ["/flyway/flyway"]
CMD ["migrate"]
