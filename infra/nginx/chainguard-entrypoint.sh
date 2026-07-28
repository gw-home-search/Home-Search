#!/usr/bin/env sh
set -eu

template="${NGINX_TEMPLATE_PATH:-/etc/nginx/templates/default.conf.template}"
rendered="${NGINX_RENDERED_CONFIG:-/tmp/home-search-default.conf}"
variables="${NGINX_ENVSUBST_VARIABLES:?NGINX_ENVSUBST_VARIABLES is required}"

test -r "${template}"
envsubst "${variables}" <"${template}" >"${rendered}"
exec /usr/sbin/nginx -c /etc/nginx/nginx.conf -e /dev/stderr -g 'daemon off;'
