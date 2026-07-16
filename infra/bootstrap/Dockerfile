FROM postgres:17-bookworm

USER root
RUN apt-get update \
    && apt-get install --yes --no-install-recommends awscli ca-certificates jq openssl \
    && find /var/lib/apt/lists -mindepth 1 -delete \
    && groupadd --gid 10001 home-search \
    && useradd --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin home-search

COPY --chown=10001:10001 --chmod=0555 infra/bootstrap/home-search-bootstrap.sh /usr/local/bin/home-search-bootstrap

USER 10001:10001
ENTRYPOINT ["/usr/local/bin/home-search-bootstrap"]
CMD ["secret-bootstrap"]
