FROM eclipse-temurin:21-jdk@sha256:da9d3a4f7650db39b918fc5a2c3da76556fb8cc8e5f3767cdea0bb409286951a AS build

WORKDIR /workspace/apps/source-data
COPY apps/source-data .
COPY apps/property-data/gradlew /workspace/apps/property-data/gradlew
COPY apps/property-data/gradle /workspace/apps/property-data/gradle
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre@sha256:273396ed5998598ed1091e8d72711c2d36980a0e65103859c55a4e977a41ffd3
RUN groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/source-data/build/libs/source-data-migration.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
