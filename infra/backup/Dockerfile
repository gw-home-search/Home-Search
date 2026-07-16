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

USER 10001:10001
WORKDIR /backup
ENTRYPOINT ["/usr/local/bin/home-search-db-backup"]
CMD ["--backup-all", "/backup"]

