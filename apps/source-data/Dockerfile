FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b AS build

WORKDIR /workspace/apps/source-data
COPY apps/source-data .
COPY apps/property-data/gradlew /workspace/apps/property-data/gradlew
COPY apps/property-data/gradle /workspace/apps/property-data/gradle
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    chmod +x gradlew \
    && ./gradlew bootJar --no-daemon

FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b
RUN addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/source-data/build/libs/source-data-migration.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
