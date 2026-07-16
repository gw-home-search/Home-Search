FROM postgres:17-bookworm

USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends awscli ca-certificates \
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

USER 10001:10001
WORKDIR /backup
ENV HOME_BACKUP_REPO_ROOT=/opt/home-search
ENTRYPOINT ["/usr/local/bin/home-search-db-backup"]
CMD ["--backup-all", "/backup"]
