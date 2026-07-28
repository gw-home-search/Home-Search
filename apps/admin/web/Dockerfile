FROM node:22-bookworm-slim@sha256:6c74791e557ce11fc957704f6d4fe134a7bc8d6f5ca4403205b2966bd488f6b3 AS build

WORKDIR /workspace
COPY apps/admin/web/package.json apps/admin/web/package-lock.json ./
RUN --mount=type=cache,id=home-search-admin-web-npm,target=/root/.npm npm ci
COPY apps/admin/web .
RUN npm run build

FROM cgr.dev/chainguard/nginx:latest-dev@sha256:22ee56150b99f1d1955637a96f1b0b9a9a6c047bbc48fe5e5b9004155f0e9087

USER 0
RUN apk add --no-cache gettext

ENV ADMIN_API_HOST=admin-api \
    ADMIN_API_PORT=8081 \
    NGINX_ENVSUBST_VARIABLES='$ADMIN_API_HOST $ADMIN_API_PORT'

COPY --from=build /workspace/dist /usr/share/nginx/html
COPY infra/nginx/admin-gateway.conf.template /etc/nginx/templates/default.conf.template
COPY infra/nginx/chainguard-nginx.conf /etc/nginx/nginx.conf
COPY --chmod=0555 infra/nginx/chainguard-entrypoint.sh /usr/local/bin/home-search-nginx-entrypoint

USER 65532:65532

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["/usr/local/bin/home-search-nginx-entrypoint"]
