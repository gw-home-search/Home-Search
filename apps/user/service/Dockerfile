FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b AS build

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

FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b AS app
RUN addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/user/service/app/build/libs/user-service-app.jar /app/application.jar
USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null -T 3 http://127.0.0.1:8082/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/application.jar"]

FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b AS worker
RUN addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/apps/user/service/worker/build/libs/user-insight-worker.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]

FROM --platform=linux/amd64 redgate/flyway:13.0-alpine@sha256:6a67d90135c8ef73299a7486da54b88f285426eea4ea1947372ffbc7b52a327b AS flyway-source

FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b AS flyway
USER root
COPY --from=flyway-source /flyway /flyway
RUN apk add --no-cache bash \
    && find /flyway/drivers -mindepth 1 ! -name 'postgresql-*.jar' -delete \
    && find /flyway/lib/flyway -mindepth 1 -maxdepth 1 -type f \
        ! -name 'flyway-commandline-*.jar' \
        ! -name 'flyway-command-mcp-*.jar' \
        ! -name 'flyway-core-*.jar' \
        ! -name 'flyway-database-postgresql-*.jar' \
        ! -name 'flyway-key-licensing-*.jar' \
        ! -name 'flyway-licensing-core-*.jar' \
        ! -name 'flyway-redgate-licensing-*.jar' \
        ! -name 'flyway-mcp-server-*.jar' \
        ! -name 'flyway-verb-info-*.jar' \
        ! -name 'flyway-verb-migrate-*.jar' \
        ! -name 'flyway-verb-validate-*.jar' -delete \
    && find /flyway/lib/rgcompare -depth -delete \
    && find /flyway/lib/netty -depth -delete \
    && find /flyway/lib/aad -depth -delete \
    && find /flyway/lib/opentelemetry -depth -delete \
    && find /flyway/lib/oracle_wallet -depth -delete \
    && find /flyway/lib/sqlfluff -depth -delete \
    && mkdir -p /flyway/sql /flyway/conf \
    && addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search \
    && chown -R 10001:10001 /flyway/sql /flyway/conf
COPY --chown=10001:10001 apps/user/service/db/migration/user /flyway/sql
COPY --chown=10001:10001 apps/user/service/db/flyway.conf /flyway/conf/flyway.conf
USER 10001:10001
ENTRYPOINT ["/flyway/flyway"]
CMD ["migrate"]
