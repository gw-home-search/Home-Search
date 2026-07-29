#!/usr/bin/env sh
set -eu

tls_directory='/run/home-search-postgres-tls'
certificate="${tls_directory}/server.crt"
private_key="${tls_directory}/server.key"
mkdir -p "${tls_directory}"
if [ ! -s "${certificate}" ] || [ ! -s "${private_key}" ]; then
  temporary_directory="$(mktemp -d "${tls_directory}/.tls.XXXXXX")"
  trap 'rm -rf "${temporary_directory}"' EXIT HUP INT TERM
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
  PGDATA="${PGDATA:-/var/lib/postgresql/data}" /docker-entrypoint-initdb.d/99-budget-hba.sh
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
