FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY apps/property-data apps/property-data
COPY libs/rtms-ingest-core libs/rtms-ingest-core
COPY libs/security-jwt-core libs/security-jwt-core
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    cd apps/property-data \
    && chmod +x gradlew \
    && ./gradlew :api:bootJar :api:verifyApiRuntimeDatabaseBoundary --no-daemon

FROM eclipse-temurin:21-jre

RUN groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/property-data/api/build/libs/property-data-api.jar /app/application.jar

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD timeout 3 bash -c '</dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
