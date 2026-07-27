FROM eclipse-temurin:21-jdk@sha256:da9d3a4f7650db39b918fc5a2c3da76556fb8cc8e5f3767cdea0bb409286951a AS build

WORKDIR /workspace
COPY apps/property-data apps/property-data
COPY libs/rtms-ingest-core libs/rtms-ingest-core
COPY libs/security-jwt-core libs/security-jwt-core
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    cd apps/property-data \
    && chmod +x gradlew \
    && ./gradlew :batch:bootJar --no-daemon

FROM eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3

RUN groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/property-data/batch/build/libs/property-data-batch.jar /app/application.jar

USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
