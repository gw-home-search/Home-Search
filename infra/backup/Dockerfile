FROM cgr.dev/chainguard/wolfi-base:latest@sha256:003627df3c1e1bba0c4116afcddb314aca9594ee2328c7e876a8081a6c988b2e

USER root
RUN apk add --no-cache \
        aws-cli-2 bash ca-certificates jq postgis postgresql-17 postgresql-17-client \
        python-3.14 zstd \
    && addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search \
    && mkdir -p /backup \
    && chown 10001:10001 /backup

COPY --chown=10001:10001 --chmod=0555 infra/backup/home-search-db-backup.sh /usr/local/bin/home-search-db-backup
COPY --chown=10001:10001 --chmod=0555 infra/budget/run-pg-backup.sh /usr/local/bin/run-budget-pg-backup
COPY --chown=10001:10001 apps/property-data/db/migration/api /opt/home-search/apps/property-data/db/migration/api
COPY --chown=10001:10001 apps/admin/service/migration/src/main/resources/db/migration/admin /opt/home-search/apps/admin/service/migration/src/main/resources/db/migration/admin
COPY --chown=10001:10001 apps/user/service/db/migration/user /opt/home-search/apps/user/service/db/migration/user
COPY --chown=10001:10001 apps/ai/ai_service/datasets/migrations /opt/home-search/apps/ai/ai_service/datasets/migrations
COPY --chown=10001:10001 apps/source-data/src/main/resources/db/migration/coordinate-source /opt/home-search/apps/source-data/src/main/resources/db/migration/coordinate-source
COPY --chown=10001:10001 --chmod=0555 infra/migration/data_only_migration.py /opt/home-search/infra/migration/data_only_migration.py
COPY --chown=10001:10001 infra/migration/data-only-allowlist.json /opt/home-search/infra/migration/data-only-allowlist.json
COPY --chown=10001:10001 --chmod=0555 infra/migration/run-s3-data-migration.sh /usr/local/bin/run-s3-data-migration

USER 10001:10001
WORKDIR /backup
ENV HOME_BACKUP_REPO_ROOT=/opt/home-search
ENTRYPOINT ["/usr/local/bin/home-search-db-backup"]
CMD ["--backup-all", "/backup"]
