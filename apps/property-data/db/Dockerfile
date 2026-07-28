FROM --platform=linux/amd64 redgate/flyway:13.0-alpine@sha256:6a67d90135c8ef73299a7486da54b88f285426eea4ea1947372ffbc7b52a327b AS flyway-source

FROM amazoncorretto:21-alpine@sha256:58c1d555f4ff3be0cfe90d3b4d1762bde080b57afbb71d48657b9d22748cad5b
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
COPY --chown=10001:10001 apps/property-data/db/migration/api /flyway/sql
COPY --chown=10001:10001 apps/property-data/db/flyway.conf /flyway/conf/flyway.conf

USER 10001:10001
ENTRYPOINT ["/flyway/flyway"]
CMD ["migrate"]
