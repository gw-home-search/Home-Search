FROM cgr.dev/chainguard/wolfi-base:latest@sha256:003627df3c1e1bba0c4116afcddb314aca9594ee2328c7e876a8081a6c988b2e

USER root
RUN apk add --no-cache \
        aws-cli-2 bash ca-certificates jq openssl postgresql-17-client \
    && addgroup -S -g 10001 home-search \
    && adduser -S -D -H -u 10001 -G home-search -s /sbin/nologin home-search

COPY --chown=10001:10001 --chmod=0555 infra/bootstrap/home-search-bootstrap.sh /usr/local/bin/home-search-bootstrap
COPY --chown=10001:10001 infra/migration/data-only-allowlist.json /opt/home-search/infra/migration/data-only-allowlist.json

USER 10001:10001
ENTRYPOINT ["/usr/local/bin/home-search-bootstrap"]
CMD ["secret-bootstrap"]
