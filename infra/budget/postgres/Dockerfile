FROM postgis/postgis:17-3.5-alpine@sha256:a5a84b5c220f20adc0246fdaf9a08988024e193be18365d4431657d681e65cdd

ENV POSTGRES_INITDB_ARGS="--auth-host=scram-sha-256 --auth-local=scram-sha-256"

USER 70:70

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
  CMD pg_isready -h 127.0.0.1 -p 5432 || exit 1

EXPOSE 5432
