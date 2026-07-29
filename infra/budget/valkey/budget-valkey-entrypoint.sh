#!/usr/bin/env sh
set -eu

for name in VALKEY_ADMIN_PASSWORD VALKEY_PROPERTY_PASSWORD VALKEY_BFF_PASSWORD; do
  eval "value=\${${name}:-}"
  case "${value}" in
    UNSET|*[!A-Za-z0-9_-]*|'')
      echo "refusing Valkey startup: ${name} is not a generated URL-safe value" >&2
      exit 1
      ;;
  esac
  [ "${#value}" -ge 32 ] || {
    echo "refusing Valkey startup: ${name} is too short" >&2
    exit 1
  }
done

umask 077
cat >/data/users.acl <<ACL
user default off
user admin on >${VALKEY_ADMIN_PASSWORD} ~* +@all
user property on >${VALKEY_PROPERTY_PASSWORD} ~home-search:map:* ~home-search:nearby-place:* ~home-search:prediction:* ~market-news:* +@read +@write +eval +evalsha -@admin -flushall -flushdb -config -acl -replicaof -slaveof
user bff on >${VALKEY_BFF_PASSWORD} ~home-search:chatbot:rate-limit* +get +set +del +incr +pexpire +eval +evalsha -@admin -flushall -flushdb -config -acl -replicaof -slaveof
ACL

exec "$@"
