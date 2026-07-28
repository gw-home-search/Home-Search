FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b AS build

WORKDIR /workspace
COPY apps/property-data apps/property-data
COPY libs/rtms-ingest-core libs/rtms-ingest-core
COPY libs/security-jwt-core libs/security-jwt-core
RUN --mount=type=cache,id=home-search-gradle,target=/root/.gradle \
    cd apps/property-data \
    && chmod +x gradlew \
    && ./gradlew :batch:bootJar --no-daemon

FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b

RUN addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/property-data/batch/build/libs/property-data-batch.jar /app/application.jar

USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
