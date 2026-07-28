FROM postgres:17-bookworm@sha256:4f736ae292687621d4dbe0d499ffd024a36bd2ee7d8ca6f2ccd4c800f047b394

USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends awscli ca-certificates postgresql-17-postgis-3 zstd \
    && find /var/lib/apt/lists -mindepth 1 -delete \
    && groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search \
    && mkdir -p /backup \
    && chown 10001:10001 /backup

COPY --chown=10001:10001 --chmod=0555 infra/backup/home-search-db-backup.sh /usr/local/bin/home-search-db-backup
COPY --chown=10001:10001 apps/property-data/db/migration/api /opt/home-search/apps/property-data/db/migration/api
COPY --chown=10001:10001 apps/admin/service/migration/src/main/resources/db/migration/admin /opt/home-search/apps/admin/service/migration/src/main/resources/db/migration/admin
COPY --chown=10001:10001 apps/user/service/db/migration/user /opt/home-search/apps/user/service/db/migration/user
COPY --chown=10001:10001 apps/ai/ai_service/datasets/migrations /opt/home-search/apps/ai/ai_service/datasets/migrations
COPY --chown=10001:10001 apps/source-data/src/main/resources/db/migration/coordinate-source /opt/home-search/apps/source-data/src/main/resources/db/migration/coordinate-source
COPY --chown=10001:10001 --chmod=0555 infra/migration/data_only_migration.py /opt/home-search/infra/migration/data_only_migration.py
COPY --chown=10001:10001 infra/migration/data-only-allowlist.json /opt/home-search/infra/migration/data-only-allowlist.json

USER 10001:10001
WORKDIR /backup
ENV HOME_BACKUP_REPO_ROOT=/opt/home-search
ENTRYPOINT ["/usr/local/bin/home-search-db-backup"]
CMD ["--backup-all", "/backup"]
