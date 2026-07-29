#!/usr/bin/env sh
set -eu

cat >"${PGDATA}/pg_hba.conf" <<'HBA'
local   all  all                       trust
hostssl all  all  172.31.255.0/24      scram-sha-256
hostssl all  all  127.0.0.1/32         scram-sha-256
hostnossl all all 0.0.0.0/0            reject
hostnossl all all ::/0                 reject
HBA
