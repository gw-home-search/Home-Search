#!/usr/bin/env sh
set -eu

tls_directory='/run/home-search-postgres-tls'
certificate="${tls_directory}/server.crt"
private_key="${tls_directory}/server.key"
mkdir -p "${tls_directory}"
if [ ! -s "${certificate}" ] || [ ! -s "${private_key}" ]; then
  temporary_directory="$(mktemp -d "${tls_directory}/.tls.XXXXXX")"
  trap 'find "${temporary_directory}" -depth -delete 2>/dev/null || true' EXIT HUP INT TERM
  openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -subj '/CN=home-search-budget-postgres' \
    -keyout "${temporary_directory}/server.key" \
    -out "${temporary_directory}/server.crt" >/dev/null 2>&1
  chmod 0600 "${temporary_directory}/server.key"
  chmod 0644 "${temporary_directory}/server.crt"
  mv "${temporary_directory}/server.key" "${private_key}"
  mv "${temporary_directory}/server.crt" "${certificate}"
  rmdir "${temporary_directory}"
  trap - EXIT HUP INT TERM
fi
chmod 0600 "${private_key}"

if [ -s "${PGDATA:-/var/lib/postgresql/data}/PG_VERSION" ]; then
  PGDATA="${PGDATA:-/var/lib/postgresql/data}"
  export PGDATA
  /docker-entrypoint-initdb.d/99-budget-hba.sh

  reconcile_directory="$(mktemp -d)"
  reconcile_started=false
  reconcile_cleanup() {
    if [ "${reconcile_started}" = true ]; then
      pg_ctl -D "${PGDATA}" -m fast -w stop >/dev/null 2>&1 || true
    fi
    find "${reconcile_directory}" -depth -delete 2>/dev/null || true
  }
  trap reconcile_cleanup EXIT
  trap 'exit 130' HUP INT TERM

  pg_ctl -D "${PGDATA}" \
    -l "${reconcile_directory}/postgres.log" \
    -o "-c listen_addresses='' -c ssl=off -c password_encryption=scram-sha-256 -c unix_socket_directories='${reconcile_directory}'" \
    -w start >/dev/null
  reconcile_started=true
  psql -X -q -v ON_ERROR_STOP=1 \
    -h "${reconcile_directory}" -U "${POSTGRES_USER}" -d postgres \
    -f /docker-entrypoint-initdb.d/15-budget-role-reconcile.sql >/dev/null
  pg_ctl -D "${PGDATA}" -m fast -w stop >/dev/null
  reconcile_started=false
  find "${reconcile_directory}" -depth -delete
  trap - EXIT HUP INT TERM
fi

exec docker-entrypoint.sh "$@" \
  -c ssl=on \
  -c ssl_cert_file="${certificate}" \
  -c ssl_key_file="${private_key}" \
  -c password_encryption=scram-sha-256 \
  -c fsync=on \
  -c synchronous_commit=on \
  -c full_page_writes=on \
  -c shared_buffers=1GB \
  -c max_connections=64 \
  -c work_mem=8MB \
  -c maintenance_work_mem=256MB
